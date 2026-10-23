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

import java.util.EnumSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class TdsTextPtrMoneyXmlTest {

    private static final TdsFrameCodec CODEC = new TdsFrameCodec();

    @Test
    void moneyRoundTripAndIsEncodable() {
        assertThat(TdsBinaryValues.isEncodable(TdsTypeCode.MONEYTYPE)).isTrue();
        assertThat(TdsBinaryValues.isEncodable(TdsTypeCode.MONEY4TYPE)).isTrue();
        assertThat(TdsBinaryValues.isEncodable(TdsTypeCode.MONEYN)).isTrue();

        TdsColumnDefinition money = new TdsColumnDefinition(
                "m", TdsTypeCode.MONEYTYPE, 8, 0, 0, 0, 8, false, null);
        byte[] wire = TdsBinaryValues.encode(money, MaskedValue.ofText("12.3456", StandardCharsets.UTF_8));
        assertThat(wire).hasSize(8);
        assertThat(TdsBinaryValues.decodeToText(money, wire)).isEqualTo("12.3456");

        TdsColumnDefinition small = new TdsColumnDefinition(
                "s", TdsTypeCode.MONEY4TYPE, 4, 0, 0, 0, 4, false, null);
        byte[] smallWire = TdsBinaryValues.encode(small, MaskedValue.ofText("1.50", StandardCharsets.UTF_8));
        assertThat(smallWire).hasSize(4);
        assertThat(TdsBinaryValues.decodeToText(small, smallWire)).isEqualTo("1.5000");
    }

    @Test
    void textPtrRoundTripTextAndNull() {
        byte[] meta = TdsTestFrames.textColumnMeta("body", false, true);
        TdsColumnDefinition col = TdsColMetadata.tryParse(meta, 0).orElseThrow().columns().get(0);
        assertThat(col.type()).isEqualTo(TdsTypeCode.TEXT);
        assertThat(col.supportsNonNullRewrite()).isTrue();
        assertThat(col.supportsNullRewrite()).isTrue();

        byte[] row = TdsTestFrames.textPtrRow("secret-text", false);
        Optional<TdsRowCodec.ParsedRow> parsed = TdsRowCodec.tryParse(row, 0, List.of(col));
        assertThat(parsed).isPresent();
        assertThat(new String(parsed.get().values().get(0).bytes(), StandardCharsets.UTF_8))
                .isEqualTo("secret-text");

        byte[] encoded = TdsRowCodec.encode(List.of(col),
                List.of(MaskedValue.ofText("REDACTED", StandardCharsets.UTF_8)));
        Optional<TdsRowCodec.ParsedRow> again = TdsRowCodec.tryParse(encoded, 0, List.of(col));
        assertThat(again).isPresent();
        assertThat(new String(again.get().values().get(0).bytes(), StandardCharsets.UTF_8))
                .isEqualTo("REDACTED");

        byte[] nullRow = TdsTestFrames.textPtrNullRow();
        assertThat(TdsRowCodec.tryParse(nullRow, 0, List.of(col)).orElseThrow().values().get(0).isNull())
                .isTrue();
    }

    @Test
    void varbinaryMaxPlpRoundTrip() {
        byte[] meta = TdsTestFrames.varbinaryMaxColumnMeta("blob", true);
        TdsColumnDefinition col = TdsColMetadata.tryParse(meta, 0).orElseThrow().columns().get(0);
        assertThat(col.plp()).isTrue();
        assertThat(col.supportsNonNullRewrite()).isTrue();

        byte[] data = new byte[]{0x01, 0x02, 0x03, 0x04};
        byte[] row = TdsTestFrames.varbinaryPlpRow(data);
        Optional<TdsRowCodec.ParsedRow> parsed = TdsRowCodec.tryParse(row, 0, List.of(col));
        assertThat(parsed).isPresent();
        assertThat(parsed.get().values().get(0).bytes()).containsExactly(data);

        byte[] encoded = TdsRowCodec.encode(List.of(col),
                List.of(MaskedValue.ofText("XX", StandardCharsets.UTF_8)));
        Optional<TdsRowCodec.ParsedRow> again = TdsRowCodec.tryParse(encoded, 0, List.of(col));
        assertThat(again).isPresent();
        assertThat(again.get().values().get(0).bytes())
                .containsExactly("XX".getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void parsesXmlAndUdtMetadataNullOnly() {
        byte[] xmlMeta = TdsTestFrames.xmlColumnMeta("doc", true);
        TdsColumnDefinition xml = TdsColMetadata.tryParse(xmlMeta, 0).orElseThrow().columns().get(0);
        assertThat(xml.type()).isEqualTo(TdsTypeCode.XML);
        assertThat(xml.plp()).isTrue();
        assertThat(xml.supportsNullRewrite()).isTrue();
        assertThat(xml.supportsNonNullRewrite()).isFalse();

        byte[] udtMeta = TdsTestFrames.udtColumnMeta("geo", true);
        TdsColumnDefinition udt = TdsColMetadata.tryParse(udtMeta, 0).orElseThrow().columns().get(0);
        assertThat(udt.type()).isEqualTo(TdsTypeCode.UDT);
        assertThat(udt.plp()).isTrue();
        assertThat(udt.supportsNonNullRewrite()).isFalse();
        assertThat(udt.supportsNullRewrite()).isTrue();
    }

    @Test
    void interceptorRewritesMoneyAndTextPtr() {
        assertForwardMutated(
                TdsTestFrames.concat(
                        TdsTestFrames.moneyColumnMeta("price", false, false),
                        TdsTestFrames.moneyRow("99.9900", 8),
                        TdsTestFrames.doneFinal()),
                new FixedValueRule("price-zero", 10, ColumnSelector.named("price"), "0.0000"));

        assertForwardMutated(
                TdsTestFrames.concat(
                        TdsTestFrames.textColumnMeta("body", false, true),
                        TdsTestFrames.textPtrRow("secret-text", false),
                        TdsTestFrames.doneFinal()),
                new FixedValueRule("body-fixed", 10, ColumnSelector.named("body"), "x"));
    }

    @Test
    void interceptorNullsXmlAndDeniesXmlNonNullRewrite() {
        SqlServerSession session = new SqlServerSession("xml-null");
        SqlServerDatabaseEventExtractor extractor =
                new SqlServerDatabaseEventExtractor("SQLServer", session.getConnectionId(), session);
        byte[] payload = TdsTestFrames.concat(
                TdsTestFrames.xmlColumnMeta("doc", true),
                TdsTestFrames.plpOpaqueRow(new byte[]{0x01, 0x02}),
                TdsTestFrames.doneFinal());
        byte[] packet = CODEC.packet(TdsPacketType.TABULAR_RESULT.getCode(), payload, 1);
        extractor.inspect(TrafficDirection.TARGET_TO_CLIENT, packet, 0, packet.length);

        SqlServerResultSetMaskingInterceptor nulling = interceptor(extractor,
                new NullingRule("doc-null", 10, ColumnSelector.named("doc")));
        TrafficDecision nulled = nulling.intercept(
                RawBackedMessage.of(TrafficDirection.TARGET_TO_CLIENT, packet, 0, packet.length));
        assertThat(nulled.action()).isEqualTo(TrafficAction.FORWARD);
        assertThat(nulled.message().mutated()).isTrue();

        SqlServerSession session2 = new SqlServerSession("xml-fixed");
        SqlServerDatabaseEventExtractor extractor2 =
                new SqlServerDatabaseEventExtractor("SQLServer", session2.getConnectionId(), session2);
        extractor2.inspect(TrafficDirection.TARGET_TO_CLIENT, packet, 0, packet.length);
        MaskingRule otherFixed = new MaskingRule() {
            @Override public String name() { return "doc-other"; }
            @Override public int priority() { return 10; }
            @Override public Set<ColumnMetadata.Category> supportedCategories() {
                return EnumSet.of(ColumnMetadata.Category.OTHER);
            }
            @Override public boolean matches(ColumnMetadata column) {
                return "doc".equals(column.name());
            }
            @Override public MaskedValue mask(ColumnMetadata column, MaskedValue original) {
                return MaskedValue.ofText("<x/>", StandardCharsets.UTF_8);
            }
        };
        SqlServerResultSetMaskingInterceptor forced = interceptor(extractor2, otherFixed);
        TrafficDecision denied = forced.intercept(
                RawBackedMessage.of(TrafficDirection.TARGET_TO_CLIENT, packet, 0, packet.length));
        assertThat(denied.action()).isEqualTo(TrafficAction.DENY);
    }

    @Test
    void interceptorRewritesVarbinaryMax() {
        assertForwardMutated(
                TdsTestFrames.concat(
                        TdsTestFrames.varbinaryMaxColumnMeta("blob", true),
                        TdsTestFrames.varbinaryPlpRow(new byte[]{9, 9, 9}),
                        TdsTestFrames.doneFinal()),
                new FixedValueRule("blob-fixed", 10, ColumnSelector.named("blob"), "ZZ"));
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
