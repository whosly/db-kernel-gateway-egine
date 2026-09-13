package com.whosly.gateway.adapter.mysql;

import com.whosly.gateway.adapter.protocol.MessageBounder;
import com.whosly.gateway.adapter.protocol.RawBackedMessage;
import com.whosly.gateway.adapter.protocol.TrafficAction;
import com.whosly.gateway.adapter.protocol.TrafficDecision;
import com.whosly.gateway.adapter.protocol.TrafficDirection;
import com.whosly.gateway.adapter.protocol.WireMessage;
import com.whosly.gateway.masking.ColumnMetadata;
import com.whosly.gateway.masking.ColumnSelector;
import com.whosly.gateway.masking.FixedValueRule;
import com.whosly.gateway.masking.MaskingEngine;
import com.whosly.gateway.masking.MaskingRule;
import com.whosly.gateway.masking.MaskingRuleRegistry;
import com.whosly.gateway.masking.NullingRule;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MySQLResultSetMaskingInterceptorTest {

    private static final int UTF8_COLLATION = 255;
    private static final int BINARY_COLLATION = 63;
    private static final MySQLFrameCodec FRAME_CODEC = new MySQLFrameCodec();
    private static final int HEADER_LENGTH = MySQLFrameCodec.HEADER_LENGTH;

    @Test
    void masksOnlyTheColumnARuleClaims() {
        MySQLDatabaseEventExtractor extractor = commandPhaseExtractor();
        MySQLResultSetMaskingInterceptor interceptor = interceptor(extractor,
                new NullingRule("null-email", 10, ColumnSelector.named("email")));
        feedResultSet(extractor, MySQLCommandType.COM_QUERY,
                column("id", 0x08), column("email", 0x0F));

        byte[] row = FRAME_CODEC.packet(rowPayload("7", "alice@example.com"), 5);
        observeTarget(extractor, row);
        assertThat(extractor.pendingCommand()).contains(MySQLCommandType.COM_QUERY);
        assertThat(extractor.currentResultSetColumns())
                .extracting(ColumnMetadata::format)
                .containsOnly(ColumnMetadata.ValueFormat.TEXT);

        TrafficDecision decision = interceptor.intercept(
                RawBackedMessage.of(TrafficDirection.TARGET_TO_CLIENT, row, 0, row.length));

        assertThat(decision.action()).isEqualTo(TrafficAction.FORWARD);
        assertThat(decision.message().mutated()).isTrue();
        List<byte[]> values = MySQLTextRow.parse(decision.message().outputBytes(), HEADER_LENGTH,
                decision.message().outputLength() - HEADER_LENGTH).orElseThrow();
        // The column no rule claims keeps its exact bytes; the claimed one becomes NULL.
        assertThat(values.get(0)).isEqualTo(bytes("7"));
        assertThat(values.get(1)).isNull();
    }

    @Test
    void keepsThePacketHeaderOfTheRewrittenRow() {
        MySQLDatabaseEventExtractor extractor = commandPhaseExtractor();
        MySQLResultSetMaskingInterceptor interceptor = interceptor(extractor,
                new FixedValueRule("fixed", 10, ColumnSelector.named("token"), "***"));
        feedResultSet(extractor, MySQLCommandType.COM_QUERY, column("token", 0x0F));

        byte[] row = FRAME_CODEC.packet(rowPayload("secret-token"), 9);
        observeTarget(extractor, row);

        byte[] output = interceptor.intercept(
                        RawBackedMessage.of(TrafficDirection.TARGET_TO_CLIENT, row, 0, row.length))
                .message().outputBytes();

        // Same sequence id, and a length that matches the rewritten payload.
        assertThat(MySQLFrameCodec.sequenceId(output, 0, output.length)).isEqualTo(9);
        assertThat(MySQLFrameCodec.payloadLength(output, 0, output.length)).isEqualTo(output.length - HEADER_LENGTH);
        assertThat(MySQLTextRow.parse(output, HEADER_LENGTH, output.length - HEADER_LENGTH).orElseThrow())
                .singleElement()
                .satisfies(value -> assertThat(new String(value, StandardCharsets.US_ASCII)).isEqualTo("***"));
    }

    @Test
    void forwardsRowsUntouchedWhenNoRuleClaimsAnyColumn() {
        MySQLDatabaseEventExtractor extractor = commandPhaseExtractor();
        MySQLResultSetMaskingInterceptor interceptor = interceptor(extractor,
                new NullingRule("null-email", 10, ColumnSelector.named("email")));
        feedResultSet(extractor, MySQLCommandType.COM_QUERY, column("id", 0x08));

        byte[] row = FRAME_CODEC.packet(rowPayload("7"), 3);
        observeTarget(extractor, row);
        WireMessage message = RawBackedMessage.of(TrafficDirection.TARGET_TO_CLIENT, row, 0, row.length);

        TrafficDecision decision = interceptor.intercept(message);

        assertThat(decision.action()).isEqualTo(TrafficAction.FORWARD);
        // Untouched means untouched: not re-encoded, not even copied.
        assertThat(decision.message().mutated()).isFalse();
        assertThat(decision.message().outputBytes()).isSameAs(row);
    }

    @Test
    void refusesARowWhenAColumnDefinitionCouldNotBeObserved() {
        MySQLDatabaseEventExtractor extractor = commandPhaseExtractor();
        MySQLResultSetMaskingInterceptor interceptor = interceptor(extractor,
                new NullingRule("null-email", 10, ColumnSelector.named("email")));
        // The second definition is truncated, so its column cannot be identified.
        feedResultSetWithRawDefinition(extractor, MySQLCommandType.COM_QUERY,
                columnPayload("id", 0x08, UTF8_COLLATION), new byte[]{0x01, 'x'});

        byte[] row = FRAME_CODEC.packet(rowPayload("7", "alice@example.com"), 5);
        observeTarget(extractor, row);

        TrafficDecision decision = interceptor.intercept(
                RawBackedMessage.of(TrafficDirection.TARGET_TO_CLIENT, row, 0, row.length));

        // A value could not be attributed to the column a rule claims, so returning the
        // row unmasked is not an option.
        assertThat(decision.action()).isEqualTo(TrafficAction.DENY);
    }

    @Test
    void refusesAColumnWhoseValuesAreNotText() {
        MySQLDatabaseEventExtractor extractor = commandPhaseExtractor();
        MySQLResultSetMaskingInterceptor interceptor = interceptor(extractor,
                new NullingRule("null-email", 10, ColumnSelector.named("email")));
        // A prepared-statement execution carries binary values in the same shape.
        feedResultSet(extractor, MySQLCommandType.COM_STMT_EXECUTE, column("email", 0x0F));

        byte[] row = FRAME_CODEC.packet(rowPayload("alice@example.com"), 4);
        observeTarget(extractor, row);

        TrafficDecision decision = interceptor.intercept(
                RawBackedMessage.of(TrafficDirection.TARGET_TO_CLIENT, row, 0, row.length));

        assertThat(decision.action()).isEqualTo(TrafficAction.DENY);
    }

    @Test
    void refusesARowWhoseValueCountDisagreesWithTheHeader() {
        MySQLDatabaseEventExtractor extractor = commandPhaseExtractor();
        MySQLResultSetMaskingInterceptor interceptor = interceptor(extractor,
                new NullingRule("null-email", 10, ColumnSelector.named("email")));
        feedResultSet(extractor, MySQLCommandType.COM_QUERY, column("id", 0x08), column("email", 0x0F));

        // Three values for two columns: attributing them would mask the wrong column.
        byte[] row = FRAME_CODEC.packet(rowPayload("7", "alice@example.com", "extra"), 5);
        observeTarget(extractor, row);

        TrafficDecision decision = interceptor.intercept(
                RawBackedMessage.of(TrafficDirection.TARGET_TO_CLIENT, row, 0, row.length));

        assertThat(decision.action()).isEqualTo(TrafficAction.DENY);
    }

    @Test
    void forwardsPacketsThatCarryNoRow() {
        MySQLDatabaseEventExtractor extractor = commandPhaseExtractor();
        MySQLResultSetMaskingInterceptor interceptor = interceptor(extractor,
                new NullingRule("null-email", 10, ColumnSelector.named("email")));
        feedResultSet(extractor, MySQLCommandType.COM_QUERY, column("email", 0x0F));

        // The terminator that ended the definitions is not a row.
        byte[] terminator = FRAME_CODEC.packet(new byte[]{(byte) 0xFE, 0x00, 0x00, 0x02, 0x00}, 5);
        observeTarget(extractor, terminator);

        TrafficDecision decision = interceptor.intercept(
                RawBackedMessage.of(TrafficDirection.TARGET_TO_CLIENT, terminator, 0, terminator.length));

        assertThat(decision.action()).isEqualTo(TrafficAction.FORWARD);
        assertThat(decision.message().mutated()).isFalse();
    }

    @Test
    void forwardsClientCommandsUntouched() {
        MySQLDatabaseEventExtractor extractor = commandPhaseExtractor();
        MySQLResultSetMaskingInterceptor interceptor = interceptor(extractor,
                new NullingRule("null-email", 10, ColumnSelector.named("email")));
        byte[] command = commandPacket(MySQLCommandType.COM_QUERY);
        extractor.inspect(TrafficDirection.CLIENT_TO_TARGET, command, 0, command.length);

        TrafficDecision decision = interceptor.intercept(
                RawBackedMessage.of(TrafficDirection.CLIENT_TO_TARGET, command, 0, command.length));

        assertThat(decision.action()).isEqualTo(TrafficAction.FORWARD);
        assertThat(decision.message().mutated()).isFalse();
    }

    @Test
    void asksForWholeMessagesOnlyInTheDirectionItRewrites() {
        MySQLResultSetMaskingInterceptor interceptor = new MySQLResultSetMaskingInterceptor(
                commandPhaseExtractor(), MaskingEngine.inactive());

        MessageBounder backend = interceptor.messageBounder(TrafficDirection.TARGET_TO_CLIENT);
        assertThat(backend).isNotNull();
        // Holding the client direction would delay commands for no benefit at all.
        assertThat(interceptor.messageBounder(TrafficDirection.CLIENT_TO_TARGET)).isNull();
    }

    @Test
    void leavesBinaryColumnsOfOtherResultSetsAlone() {
        MySQLDatabaseEventExtractor extractor = commandPhaseExtractor();
        MySQLResultSetMaskingInterceptor interceptor = interceptor(extractor,
                new NullingRule("null-token", 10, ColumnSelector.named("token")));
        // A binary column no rule claims must not stop the result set.
        feedResultSet(extractor, MySQLCommandType.COM_QUERY, column("token_hash", 0xFD, BINARY_COLLATION));

        byte[] row = FRAME_CODEC.packet(rowPayload("deadbeef"), 3);
        observeTarget(extractor, row);

        TrafficDecision decision = interceptor.intercept(
                RawBackedMessage.of(TrafficDirection.TARGET_TO_CLIENT, row, 0, row.length));

        assertThat(decision.action()).isEqualTo(TrafficAction.FORWARD);
        assertThat(decision.message().mutated()).isFalse();
    }

    private static MySQLResultSetMaskingInterceptor interceptor(MySQLDatabaseEventExtractor extractor,
                                                               MaskingRule... rules) {
        return new MySQLResultSetMaskingInterceptor(extractor,
                new MaskingEngine(new MaskingRuleRegistry(List.of(rules))));
    }

    private static MySQLDatabaseEventExtractor commandPhaseExtractor() {
        return new MySQLDatabaseEventExtractor("MySQL", "mysql-masking", true, null);
    }

    /** Drives the extractor through a whole result set, the way the relay does. */
    private static void feedResultSet(MySQLDatabaseEventExtractor extractor, MySQLCommandType command,
                                      ColumnDefinition... columns) {
        byte[][] definitions = new byte[columns.length][];
        for (int index = 0; index < columns.length; index++) {
            definitions[index] = columnPayload(columns[index].name(), columns[index].type(),
                    columns[index].collation());
        }
        feedResultSetWithRawDefinition(extractor, command, definitions);
    }

    private static void feedResultSetWithRawDefinition(MySQLDatabaseEventExtractor extractor,
                                                       MySQLCommandType command, byte[]... definitions) {
        byte[] commandPacket = commandPacket(command);
        extractor.inspect(TrafficDirection.CLIENT_TO_TARGET, commandPacket, 0, commandPacket.length);

        byte[] header = FRAME_CODEC.packet(new byte[]{(byte) definitions.length}, 1);
        extractor.inspect(TrafficDirection.TARGET_TO_CLIENT, header, 0, header.length);

        int sequence = 2;
        for (byte[] definition : definitions) {
            byte[] packet = FRAME_CODEC.packet(definition, sequence++);
            extractor.inspect(TrafficDirection.TARGET_TO_CLIENT, packet, 0, packet.length);
        }
        byte[] terminator = FRAME_CODEC.packet(new byte[]{(byte) 0xFE, 0x00, 0x00, 0x02, 0x00}, sequence);
        extractor.inspect(TrafficDirection.TARGET_TO_CLIENT, terminator, 0, terminator.length);
    }

    /** The observer drives the extractor before the rewrite phase sees the message. */
    private static void observeTarget(MySQLDatabaseEventExtractor extractor, byte[] packet) {
        extractor.inspect(TrafficDirection.TARGET_TO_CLIENT, packet, 0, packet.length);
    }

    private static byte[] commandPacket(MySQLCommandType command) {
        return FRAME_CODEC.packet(new byte[]{(byte) command.getCode()}, 0);
    }

    private static byte[] rowPayload(String... values) {
        List<byte[]> encoded = new java.util.ArrayList<>();
        for (String value : values) {
            encoded.add(value == null ? null : value.getBytes(StandardCharsets.US_ASCII));
        }
        return MySQLTextRow.encode(encoded);
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.US_ASCII);
    }

    private static ColumnDefinition column(String name, int type) {
        return new ColumnDefinition(name, type, UTF8_COLLATION);
    }

    private static ColumnDefinition column(String name, int type, int collation) {
        return new ColumnDefinition(name, type, collation);
    }

    private static byte[] columnPayload(String name, int type, int collation) {
        ByteArrayOutputStream payload = new ByteArrayOutputStream();
        writeLengthEncodedString(payload, "def");
        writeLengthEncodedString(payload, "shop");
        writeLengthEncodedString(payload, "accounts");
        writeLengthEncodedString(payload, "accounts");
        writeLengthEncodedString(payload, name);
        writeLengthEncodedString(payload, name);
        payload.write(0x0C);
        payload.write(collation & 0xFF);
        payload.write((collation >> 8) & 0xFF);
        payload.writeBytes(new byte[]{0x00, 0x00, 0x00, 0x00});
        payload.write(type);
        // Flags: nullable, so rules that refuse NOT NULL columns still apply.
        payload.writeBytes(new byte[]{0x00, 0x00});
        payload.write(0x00);
        payload.writeBytes(new byte[]{0x00, 0x00});
        return payload.toByteArray();
    }

    private static void writeLengthEncodedString(ByteArrayOutputStream payload, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.US_ASCII);
        payload.write(bytes.length);
        payload.writeBytes(bytes);
    }

    private record ColumnDefinition(String name, int type, int collation) {
    }
}
