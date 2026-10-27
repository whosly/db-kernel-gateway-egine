package com.whosly.gateway.adapter.sqlserver;

import com.whosly.gateway.adapter.protocol.RawBackedMessage;
import com.whosly.gateway.adapter.protocol.TrafficAction;
import com.whosly.gateway.adapter.protocol.TrafficDecision;
import com.whosly.gateway.adapter.protocol.TrafficDirection;
import com.whosly.gateway.masking.ColumnMetadata;
import com.whosly.gateway.masking.ColumnSelector;
import com.whosly.gateway.masking.FixedValueRule;
import com.whosly.gateway.masking.MaskedValue;
import com.whosly.gateway.masking.MaskingEngine;
import com.whosly.gateway.masking.MaskingRule;
import com.whosly.gateway.masking.MaskingRuleRegistry;
import com.whosly.gateway.masking.NullingRule;
import com.whosly.gateway.masking.PartialMaskRule;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TdsXmlUdtTvpTest {

    private static final TdsFrameCodec CODEC = new TdsFrameCodec();

    @Test
    void binXmlRoundTripSyntheticDocument() {
        byte[] wire = TdsBinXml.encodeTextDocument("hello");
        assertThat(wire[0] & 0xFF).isEqualTo(0xDF);
        assertThat(wire[1] & 0xFF).isEqualTo(0xFF);
        Optional<String> text = TdsBinXml.tryDecodeToText(wire);
        assertThat(text).contains("<m>hello</m>");

        byte[] empty = TdsBinXml.encodeTextDocument("");
        assertThat(TdsBinXml.tryDecodeToText(empty)).contains("<m></m>");
    }

    @Test
    void hierarchyIdKnownVectors() {
        assertThat(TdsHierarchyId.encode("/")).isEmpty();
        assertThat(TdsHierarchyId.decode(new byte[0])).isEqualTo("/");

        assertThat(TdsHierarchyId.encode("/0/")).containsExactly(0x48);
        assertThat(TdsHierarchyId.decode(new byte[]{0x48})).isEqualTo("/0/");

        assertThat(TdsHierarchyId.encode("/1/")).containsExactly(0x58);
        assertThat(TdsHierarchyId.decode(new byte[]{0x58})).isEqualTo("/1/");

        assertThat(TdsHierarchyId.encode("/1/1/")).containsExactly(0x5A, 0xC0);
        assertThat(TdsHierarchyId.decode(new byte[]{0x5A, (byte) 0xC0})).isEqualTo("/1/1/");

        assertThat(TdsHierarchyId.encode("/2/")).containsExactly(0x68);

        assertThatThrownBy(() -> TdsHierarchyId.encode("/1.1/"))
                .isInstanceOf(com.whosly.gateway.masking.MaskingException.class);
    }

    @Test
    void hierarchyIdColumnMetaAndInterceptorFixed() {
        byte[] meta = TdsTestFrames.udtColumnMeta("hid", true, "hierarchyid");
        TdsColumnDefinition col = TdsColMetadata.tryParse(meta, 0).orElseThrow().columns().get(0);
        assertThat(col.isHierarchyId()).isTrue();
        assertThat(col.toColumnMetadata().category()).isEqualTo(ColumnMetadata.Category.TEXT);
        assertThat(col.supportsNonNullRewrite()).isTrue();

        byte[] path = TdsHierarchyId.encode("/1/2/");
        assertForwardMutated(
                TdsTestFrames.concat(meta, TdsTestFrames.plpOpaqueRow(path), TdsTestFrames.doneFinal()),
                new FixedValueRule("hid-fixed", 10, ColumnSelector.named("hid"), "/9/"));
    }

    @Test
    void genericUdtAcceptsHexFixedDeniesPlainText() {
        byte[] meta = TdsTestFrames.udtColumnMeta("geo", true, "geometry");
        TdsColumnDefinition col = TdsColMetadata.tryParse(meta, 0).orElseThrow().columns().get(0);
        assertThat(col.isHierarchyId()).isFalse();
        assertThat(col.toColumnMetadata().category()).isEqualTo(ColumnMetadata.Category.BINARY);

        byte[] original = new byte[]{0x01, 0x02};
        assertForwardMutated(
                TdsTestFrames.concat(meta, TdsTestFrames.plpOpaqueRow(original), TdsTestFrames.doneFinal()),
                new FixedValueRule("geo-hex", 10, ColumnSelector.named("geo"), "0xAABB"));

        // Plain text fixed → encode refuses → DENY
        SqlServerSession session = new SqlServerSession("udt-deny");
        SqlServerDatabaseEventExtractor extractor =
                new SqlServerDatabaseEventExtractor("SQLServer", session.getConnectionId(), session);
        byte[] payload = TdsTestFrames.concat(
                meta, TdsTestFrames.plpOpaqueRow(original), TdsTestFrames.doneFinal());
        byte[] packet = CODEC.packet(TdsPacketType.TABULAR_RESULT.getCode(), payload, 1);
        extractor.inspect(TrafficDirection.TARGET_TO_CLIENT, packet, 0, packet.length);
        SqlServerResultSetMaskingInterceptor interceptor = interceptor(extractor,
                new FixedValueRule("geo-plain", 10, ColumnSelector.named("geo"), "not-hex"));
        TrafficDecision decision = interceptor.intercept(
                RawBackedMessage.of(TrafficDirection.TARGET_TO_CLIENT, packet, 0, packet.length));
        assertThat(decision.action()).isEqualTo(TrafficAction.DENY);
    }

    @Test
    void genericUdtNullRewrite() {
        byte[] meta = TdsTestFrames.udtColumnMeta("geo", true);
        assertForwardMutated(
                TdsTestFrames.concat(meta, TdsTestFrames.plpOpaqueRow(new byte[]{1}), TdsTestFrames.doneFinal()),
                new NullingRule("geo-null", 10, ColumnSelector.named("geo")));
    }

    @Test
    void xmlInterceptorPartialMask() {
        byte[] binxml = TdsBinXml.encodeTextDocument("abcdef");
        assertForwardMutated(
                TdsTestFrames.concat(
                        TdsTestFrames.xmlColumnMeta("doc", true),
                        TdsTestFrames.plpOpaqueRow(binxml),
                        TdsTestFrames.doneFinal()),
                new PartialMaskRule("doc-partial", 10, ColumnSelector.named("doc"), 1, 1));
    }

    @Test
    void xmlRowEncodeDecodeRoundTrip() {
        byte[] meta = TdsTestFrames.xmlColumnMeta("doc", true);
        TdsColumnDefinition col = TdsColMetadata.tryParse(meta, 0).orElseThrow().columns().get(0);
        byte[] encoded = TdsRowCodec.encode(
                List.of(col),
                List.of(MaskedValue.ofText("masked", StandardCharsets.UTF_8)));
        Optional<TdsRowCodec.ParsedRow> parsed = TdsRowCodec.tryParse(encoded, 0, List.of(col));
        assertThat(parsed).isPresent();
        assertThat(TdsBinXml.tryDecodeToText(parsed.get().values().get(0).bytes()))
                .contains("<m>masked</m>");
    }

    @Test
    void tvpColumnMetaParsesButCellRewriteDenied() {
        byte[] meta = TdsTestFrames.tvpColumnMeta("tvp", true);
        TdsColumnDefinition col = TdsColMetadata.tryParse(meta, 0).orElseThrow().columns().get(0);
        assertThat(col.type()).isEqualTo(TdsTypeCode.TVP);
        assertThat(col.udtTypeName()).contains("MyTvp");
        assertThat(col.supportsNonNullRewrite()).isFalse();
        assertThat(col.supportsNullRewrite()).isFalse();

        // ROW with a dummy byte after token — TVP layout cannot be read → DENY when rule hits.
        byte[] row = new byte[]{(byte) TdsTokenType.ROW.getCode(), 0x00};
        byte[] payload = TdsTestFrames.concat(meta, row, TdsTestFrames.doneFinal());
        SqlServerSession session = new SqlServerSession("tvp-deny");
        SqlServerDatabaseEventExtractor extractor =
                new SqlServerDatabaseEventExtractor("SQLServer", session.getConnectionId(), session);
        byte[] packet = CODEC.packet(TdsPacketType.TABULAR_RESULT.getCode(), payload, 1);
        extractor.inspect(TrafficDirection.TARGET_TO_CLIENT, packet, 0, packet.length);
        // Ensure extractor saw the TVP column
        assertThat(extractor.currentTdsColumns()).hasSize(1);

        SqlServerResultSetMaskingInterceptor interceptor = interceptor(extractor,
                new FixedValueRule("tvp-fixed", 10, ColumnSelector.named("tvp"), "x"));
        // Force rule match on OTHER category via anonymous rule if FixedValue misses OTHER
        MaskingRule force = new MaskingRule() {
            @Override public String name() { return "tvp-force"; }
            @Override public int priority() { return 10; }
            @Override public java.util.Set<ColumnMetadata.Category> supportedCategories() {
                return java.util.EnumSet.allOf(ColumnMetadata.Category.class);
            }
            @Override public boolean matches(ColumnMetadata column) {
                return "tvp".equals(column.name());
            }
            @Override public MaskedValue mask(ColumnMetadata column, MaskedValue original) {
                return MaskedValue.ofText("x", StandardCharsets.UTF_8);
            }
        };
        SqlServerResultSetMaskingInterceptor forced = interceptor(extractor, force);
        TrafficDecision decision = forced.intercept(
                RawBackedMessage.of(TrafficDirection.TARGET_TO_CLIENT, packet, 0, packet.length));
        assertThat(decision.action()).isEqualTo(TrafficAction.DENY);
    }

    @Test
    void tvpWithoutRulesForwardsByteIdentical() {
        byte[] meta = TdsTestFrames.tvpColumnMeta("tvp", true);
        byte[] row = new byte[]{(byte) TdsTokenType.ROW.getCode(), 0x00};
        byte[] payload = TdsTestFrames.concat(meta, row, TdsTestFrames.doneFinal());
        SqlServerSession session = new SqlServerSession("tvp-fwd");
        SqlServerDatabaseEventExtractor extractor =
                new SqlServerDatabaseEventExtractor("SQLServer", session.getConnectionId(), session);
        byte[] packet = CODEC.packet(TdsPacketType.TABULAR_RESULT.getCode(), payload, 1);
        extractor.inspect(TrafficDirection.TARGET_TO_CLIENT, packet, 0, packet.length);
        assertThat(extractor.currentTdsColumns()).hasSize(1);
        assertThat(extractor.lastTargetMessageContainedRow()).isTrue();

        SqlServerResultSetMaskingInterceptor interceptor = interceptor(extractor);
        TrafficDecision decision = interceptor.intercept(
                RawBackedMessage.of(TrafficDirection.TARGET_TO_CLIENT, packet, 0, packet.length));
        assertThat(decision.action()).isEqualTo(TrafficAction.FORWARD);
        assertThat(decision.message().mutated()).isFalse();
    }

    @Test
    void hexHelpers() {
        assertThat(TdsHierarchyId.tryParseHexBytes("0xAABB").orElseThrow())
                .containsExactly((byte) 0xAA, (byte) 0xBB);
        assertThat(TdsHierarchyId.toHexLiteral(new byte[]{0x01, 0x0F})).isEqualTo("0x010F");
        assertThat(TdsHierarchyId.tryParseHexBytes("nope")).isEmpty();
    }

    private static void assertForwardMutated(byte[] payload, MaskingRule rule) {
        SqlServerSession session = new SqlServerSession("t-" + rule.name());
        SqlServerDatabaseEventExtractor extractor =
                new SqlServerDatabaseEventExtractor("SQLServer", session.getConnectionId(), session);
        byte[] packet = CODEC.packet(TdsPacketType.TABULAR_RESULT.getCode(), payload, 1);
        extractor.inspect(TrafficDirection.TARGET_TO_CLIENT, packet, 0, packet.length);
        SqlServerResultSetMaskingInterceptor interceptor = interceptor(extractor, rule);
        TrafficDecision decision = interceptor.intercept(
                RawBackedMessage.of(TrafficDirection.TARGET_TO_CLIENT, packet, 0, packet.length));
        assertThat(decision.action()).isEqualTo(TrafficAction.FORWARD);
        assertThat(decision.message().mutated()).isTrue();
    }

    private static SqlServerResultSetMaskingInterceptor interceptor(
            SqlServerDatabaseEventExtractor extractor, MaskingRule... rules) {
        return new SqlServerResultSetMaskingInterceptor(extractor,
                new MaskingEngine(new MaskingRuleRegistry(List.of(rules))));
    }
}
