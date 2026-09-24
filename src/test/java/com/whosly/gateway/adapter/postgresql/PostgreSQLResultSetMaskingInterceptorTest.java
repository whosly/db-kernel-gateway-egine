package com.whosly.gateway.adapter.postgresql;

import com.whosly.gateway.adapter.protocol.MessageBounder;
import com.whosly.gateway.adapter.protocol.RawBackedMessage;
import com.whosly.gateway.adapter.protocol.TrafficAction;
import com.whosly.gateway.adapter.protocol.TrafficDecision;
import com.whosly.gateway.adapter.protocol.TrafficDirection;
import com.whosly.gateway.adapter.protocol.WireMessage;
import com.whosly.gateway.masking.ColumnMetadata;
import com.whosly.gateway.masking.ColumnSelector;
import com.whosly.gateway.masking.FixedValueRule;
import com.whosly.gateway.masking.MaskedValue;
import com.whosly.gateway.masking.MaskingEngine;
import com.whosly.gateway.masking.MaskingRule;
import com.whosly.gateway.masking.MaskingRuleRegistry;
import com.whosly.gateway.masking.NullingRule;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class PostgreSQLResultSetMaskingInterceptorTest {

    private static final PostgreSQLFrameCodec CODEC = new PostgreSQLFrameCodec();
    private static final int TYPED_HEADER_LENGTH = PostgreSQLFrameCodec.TYPED_HEADER_LENGTH;

    @Test
    void masksOnlyTheColumnARuleClaims() {
        PostgreSQLDatabaseEventExtractor extractor = extractor();
        PostgreSQLResultSetMaskingInterceptor interceptor = interceptor(extractor,
                new NullingRule("null-email", 10, ColumnSelector.named("email")));
        observe(extractor, rowDescription(field("id", PostgreSQLTypeOid.INT4.getOid()),
                field("email", PostgreSQLTypeOid.VARCHAR.getOid())));

        byte[] row = dataRow(bytes("7"), bytes("alice@example.com"));
        observe(extractor, row);
        assertThat(extractor.currentResultSetColumnCount()).isEqualTo(2);
        assertThat(extractor.currentResultSetColumns())
                .extracting(ColumnMetadata::format)
                .containsOnly(ColumnMetadata.ValueFormat.TEXT);

        TrafficDecision decision = interceptor.intercept(
                RawBackedMessage.of(TrafficDirection.TARGET_TO_CLIENT, row, 0, row.length));

        assertThat(decision.action()).isEqualTo(TrafficAction.FORWARD);
        assertThat(decision.message().mutated()).isTrue();
        List<byte[]> values = PostgreSQLDataRow.parse(decision.message().outputBytes(), TYPED_HEADER_LENGTH,
                decision.message().outputLength() - TYPED_HEADER_LENGTH).orElseThrow();
        assertThat(values.get(0)).isEqualTo(bytes("7"));
        assertThat(values.get(1)).isNull();
    }

    @Test
    void keepsTheMessageHeaderOfTheRewrittenRow() {
        PostgreSQLDatabaseEventExtractor extractor = extractor();
        PostgreSQLResultSetMaskingInterceptor interceptor = interceptor(extractor,
                new FixedValueRule("fixed", 10, ColumnSelector.named("token"), "***"));
        observe(extractor, rowDescription(field("token", PostgreSQLTypeOid.TEXT.getOid())));

        byte[] row = dataRow(bytes("secret-token"));
        observe(extractor, row);

        byte[] output = interceptor.intercept(
                        RawBackedMessage.of(TrafficDirection.TARGET_TO_CLIENT, row, 0, row.length))
                .message().outputBytes();

        // Same message type, and a length field that matches the rewritten payload.
        assertThat((char) output[0]).isEqualTo('D');
        assertThat(PostgreSQLFrameCodec.readInt4(output, 1, output.length))
                .isEqualTo(output.length - 1);
        assertThat(PostgreSQLDataRow.parse(output, TYPED_HEADER_LENGTH, output.length - TYPED_HEADER_LENGTH)
                .orElseThrow())
                .singleElement()
                .satisfies(value -> assertThat(new String(value, StandardCharsets.US_ASCII)).isEqualTo("***"));
    }

    @Test
    void forwardsRowsUntouchedWhenNoRuleClaimsAnyColumn() {
        PostgreSQLDatabaseEventExtractor extractor = extractor();
        PostgreSQLResultSetMaskingInterceptor interceptor = interceptor(extractor,
                new NullingRule("null-email", 10, ColumnSelector.named("email")));
        observe(extractor, rowDescription(field("id", PostgreSQLTypeOid.INT4.getOid())));

        byte[] row = dataRow(bytes("7"));
        observe(extractor, row);
        WireMessage message = RawBackedMessage.of(TrafficDirection.TARGET_TO_CLIENT, row, 0, row.length);

        TrafficDecision decision = interceptor.intercept(message);

        assertThat(decision.action()).isEqualTo(TrafficAction.FORWARD);
        // Untouched means untouched: not re-encoded, not even copied.
        assertThat(decision.message().mutated()).isFalse();
        assertThat(decision.message().outputBytes()).isSameAs(row);
    }

    @Test
    void refusesARowWhenTheRowDescriptionCouldNotBeObserved() {
        PostgreSQLDatabaseEventExtractor extractor = extractor();
        PostgreSQLResultSetMaskingInterceptor interceptor = interceptor(extractor,
                new NullingRule("null-email", 10, ColumnSelector.named("email")));

        // A description declaring two fields but carrying only one: the second column
        // cannot be identified, so a value could not be attributed to it.
        byte[] complete = PostgreSQLColumnMetadata.payload(List.of(
                field("id", PostgreSQLTypeOid.INT4.getOid()),
                field("email", PostgreSQLTypeOid.VARCHAR.getOid())));
        observe(extractor, CODEC.packet('T', Arrays.copyOf(complete, complete.length - 8)));

        byte[] row = dataRow(bytes("7"), bytes("alice@example.com"));
        observe(extractor, row);

        TrafficDecision decision = interceptor.intercept(
                RawBackedMessage.of(TrafficDirection.TARGET_TO_CLIENT, row, 0, row.length));

        assertThat(decision.action()).isEqualTo(TrafficAction.DENY);
    }

    @Test
    void masksABinaryColumnByNullingIt() {
        PostgreSQLDatabaseEventExtractor extractor = extractor();
        PostgreSQLResultSetMaskingInterceptor interceptor = interceptor(extractor,
                new NullingRule("null-email", 10, ColumnSelector.named("email")));
        observe(extractor, rowDescription(
                new PostgreSQLColumnMetadata.Field("email", 0, PostgreSQLTypeOid.VARCHAR.getOid(), 1)));

        byte[] row = dataRow(bytes("alice@example.com"));
        observe(extractor, row);

        TrafficDecision decision = interceptor.intercept(
                RawBackedMessage.of(TrafficDirection.TARGET_TO_CLIENT, row, 0, row.length));

        // NULL is valid for every type, so a client that asked for binary format is
        // protected just the same: nothing has to be re-encoded.
        assertThat(decision.action()).isEqualTo(TrafficAction.FORWARD);
        assertThat(decision.message().mutated()).isTrue();
        assertThat(valuesOf(decision)).singleElement().isNull();
    }

    @Test
    void masksABinaryTextColumnWithTheMaskBytes() {
        PostgreSQLDatabaseEventExtractor extractor = extractor();
        PostgreSQLResultSetMaskingInterceptor interceptor = interceptor(extractor,
                new FixedValueRule("fixed", 10, ColumnSelector.named("token"), "***"));
        observe(extractor, rowDescription(
                new PostgreSQLColumnMetadata.Field("token", 0, PostgreSQLTypeOid.VARCHAR.getOid(), 1)));

        byte[] row = dataRow(bytes("secret-token"));
        observe(extractor, row);

        TrafficDecision decision = interceptor.intercept(
                RawBackedMessage.of(TrafficDirection.TARGET_TO_CLIENT, row, 0, row.length));

        // A varchar's binary representation is its bytes, so the mask carries over.
        assertThat(decision.action()).isEqualTo(TrafficAction.FORWARD);
        assertThat(new String(valuesOf(decision).get(0), StandardCharsets.US_ASCII)).isEqualTo("***");
    }

    @Test
    void addsTheJsonbVersionByteWhenMaskingBinaryJsonb() {
        PostgreSQLDatabaseEventExtractor extractor = extractor();
        PostgreSQLResultSetMaskingInterceptor interceptor = interceptor(extractor,
                new FixedValueRule("fixed", 10, ColumnSelector.named("doc"), "{}"));
        observe(extractor, rowDescription(
                new PostgreSQLColumnMetadata.Field("doc", 0, PostgreSQLTypeOid.JSONB.getOid(), 1)));

        byte[] row = dataRow(bytes("{\"secret\":1}"));
        observe(extractor, row);

        byte[] value = valuesOf(interceptor.intercept(
                RawBackedMessage.of(TrafficDirection.TARGET_TO_CLIENT, row, 0, row.length))).get(0);

        // jsonb's binary layout is a version byte followed by the JSON text.
        assertThat(value[0]).isEqualTo((byte) 1);
        assertThat(new String(value, 1, value.length - 1, StandardCharsets.US_ASCII)).isEqualTo("{}");
    }

    @Test
    void refusesANonNullMaskOnABinaryTypeWithoutAKnownEncoding() {
        PostgreSQLDatabaseEventExtractor extractor = extractor();
        PostgreSQLResultSetMaskingInterceptor interceptor = interceptor(extractor, numericRule());
        observe(extractor, rowDescription(
                new PostgreSQLColumnMetadata.Field("age", 0, PostgreSQLTypeOid.INT4.getOid(), 1)));

        byte[] row = dataRow(bytes("42"));
        observe(extractor, row);

        TrafficDecision decision = interceptor.intercept(
                RawBackedMessage.of(TrafficDirection.TARGET_TO_CLIENT, row, 0, row.length));

        // The rule can produce a NUMERIC value, but an int4's binary layout is not
        // reproduced here, so the row is refused rather than sent as garbage.
        assertThat(decision.action()).isEqualTo(TrafficAction.DENY);
    }

    @Test
    void refusesARowWhoseFieldCountDisagreesWithTheDescription() {
        PostgreSQLDatabaseEventExtractor extractor = extractor();
        PostgreSQLResultSetMaskingInterceptor interceptor = interceptor(extractor,
                new NullingRule("null-email", 10, ColumnSelector.named("email")));
        observe(extractor, rowDescription(field("id", PostgreSQLTypeOid.INT4.getOid()),
                field("email", PostgreSQLTypeOid.VARCHAR.getOid())));

        byte[] row = dataRow(bytes("7"), bytes("alice@example.com"), bytes("extra"));
        observe(extractor, row);

        TrafficDecision decision = interceptor.intercept(
                RawBackedMessage.of(TrafficDirection.TARGET_TO_CLIENT, row, 0, row.length));

        assertThat(decision.action()).isEqualTo(TrafficAction.DENY);
    }

    @Test
    void forwardsMessagesThatCarryNoRow() {
        PostgreSQLDatabaseEventExtractor extractor = extractor();
        PostgreSQLResultSetMaskingInterceptor interceptor = interceptor(extractor,
                new NullingRule("null-email", 10, ColumnSelector.named("email")));
        observe(extractor, rowDescription(field("email", PostgreSQLTypeOid.VARCHAR.getOid())));

        byte[] commandComplete = CODEC.packet('C', "SELECT 1\0".getBytes(StandardCharsets.US_ASCII));
        observe(extractor, commandComplete);

        TrafficDecision decision = interceptor.intercept(
                RawBackedMessage.of(TrafficDirection.TARGET_TO_CLIENT, commandComplete, 0, commandComplete.length));

        assertThat(decision.action()).isEqualTo(TrafficAction.FORWARD);
        assertThat(decision.message().mutated()).isFalse();
    }

    @Test
    void forwardsFrontendMessagesUntouched() {
        PostgreSQLDatabaseEventExtractor extractor = extractor();
        PostgreSQLResultSetMaskingInterceptor interceptor = interceptor(extractor,
                new NullingRule("null-email", 10, ColumnSelector.named("email")));
        byte[] query = CODEC.packet('Q', "select 1\0".getBytes(StandardCharsets.US_ASCII));
        extractor.inspect(TrafficDirection.CLIENT_TO_TARGET, query, 0, query.length);

        TrafficDecision decision = interceptor.intercept(
                RawBackedMessage.of(TrafficDirection.CLIENT_TO_TARGET, query, 0, query.length));

        assertThat(decision.action()).isEqualTo(TrafficAction.FORWARD);
        assertThat(decision.message().mutated()).isFalse();
    }

    @Test
    void forgetsTheDescriptionWhenTheCommandCycleEnds() {
        PostgreSQLDatabaseEventExtractor extractor = extractor();
        PostgreSQLResultSetMaskingInterceptor interceptor = interceptor(extractor,
                new NullingRule("null-email", 10, ColumnSelector.named("email")));
        observe(extractor, rowDescription(field("email", PostgreSQLTypeOid.VARCHAR.getOid())));
        byte[] readyForQuery = CODEC.packet('Z', new byte[]{'I'});
        observe(extractor, readyForQuery);

        // The description belonged to a finished result set: a later row must not be
        // attributed to it, so it is refused instead of masked by a stale mapping.
        assertThat(extractor.currentResultSetColumns()).isEmpty();
        byte[] row = dataRow(bytes("alice@example.com"));
        observe(extractor, row);

        TrafficDecision decision = interceptor.intercept(
                RawBackedMessage.of(TrafficDirection.TARGET_TO_CLIENT, row, 0, row.length));

        assertThat(decision.action()).isEqualTo(TrafficAction.DENY);
    }

    @Test
    void asksForWholeMessagesOnlyInTheDirectionItRewrites() {
        PostgreSQLResultSetMaskingInterceptor interceptor =
                new PostgreSQLResultSetMaskingInterceptor(extractor(), MaskingEngine.inactive());

        assertThat(interceptor.messageBounder(TrafficDirection.TARGET_TO_CLIENT)).isNotNull();
        // Holding the frontend direction would delay queries for no benefit at all.
        assertThat(interceptor.messageBounder(TrafficDirection.CLIENT_TO_TARGET)).isNull();
    }

    /** The values of the row a decision carries. */
    private static List<byte[]> valuesOf(TrafficDecision decision) {
        return PostgreSQLDataRow.parse(decision.message().outputBytes(), TYPED_HEADER_LENGTH,
                decision.message().outputLength() - TYPED_HEADER_LENGTH).orElseThrow();
    }

    /** A rule that produces a NUMERIC value, for which no binary encoding exists here. */
    private static MaskingRule numericRule() {
        return new MaskingRule() {
            @Override
            public String name() {
                return "numeric-zero";
            }

            @Override
            public Set<ColumnMetadata.Category> supportedCategories() {
                return Set.of(ColumnMetadata.Category.NUMERIC);
            }

            @Override
            public boolean matches(ColumnMetadata column) {
                return true;
            }

            @Override
            public MaskedValue mask(ColumnMetadata column, MaskedValue original) {
                return MaskedValue.of(bytes("0"));
            }
        };
    }

    private static PostgreSQLResultSetMaskingInterceptor interceptor(PostgreSQLDatabaseEventExtractor extractor,
                                                                    MaskingRule... rules) {
        return new PostgreSQLResultSetMaskingInterceptor(extractor,
                new MaskingEngine(new MaskingRuleRegistry(List.of(rules))));
    }

    private static PostgreSQLDatabaseEventExtractor extractor() {
        String sessionId = "pg-masking";
        return new PostgreSQLDatabaseEventExtractor("PostgreSQL", sessionId, true,
                new PostgreSQLSession(sessionId));
    }

    /** The observer drives the extractor before the rewrite phase sees the message. */
    private static void observe(PostgreSQLDatabaseEventExtractor extractor, byte[] packet) {
        extractor.inspect(TrafficDirection.TARGET_TO_CLIENT, packet, 0, packet.length);
    }

    private static byte[] rowDescription(PostgreSQLColumnMetadata.Field... fields) {
        return CODEC.packet('T', PostgreSQLColumnMetadata.payload(List.of(fields)));
    }

    private static byte[] dataRow(byte[]... values) {
        return CODEC.packet('D', PostgreSQLDataRow.encode(Arrays.asList(values)));
    }

    private static PostgreSQLColumnMetadata.Field field(String name, int typeOid) {
        return new PostgreSQLColumnMetadata.Field(name, 0, typeOid, 0);
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.US_ASCII);
    }
}
