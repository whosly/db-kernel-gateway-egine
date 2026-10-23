package com.whosly.gateway.adapter.sqlserver;

import com.whosly.gateway.adapter.protocol.InterceptorPhase;
import com.whosly.gateway.adapter.protocol.MessageBounder;
import com.whosly.gateway.adapter.protocol.MessageInterceptor;
import com.whosly.gateway.adapter.protocol.TrafficDecision;
import com.whosly.gateway.adapter.protocol.TrafficDirection;
import com.whosly.gateway.adapter.protocol.WireMessage;
import com.whosly.gateway.masking.ColumnMetadata;
import com.whosly.gateway.masking.MaskedValue;
import com.whosly.gateway.masking.MaskingEngine;
import com.whosly.gateway.masking.MaskingException;
import com.whosly.gateway.masking.MaskingRule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Applies masking rules to TDS {@code ROW} tokens inside {@code TABULAR_RESULT}.
 *
 * <p>Fail-closed (rule 8.2): incomplete COLMETADATA, TEXTPTR/MONEY/XML/unknown
 * non-null rewrites, and rows that cannot be re-encoded are denied rather than
 * approximated. ROW and NBCROW text/guid/numeric/temporal (including text PLP)
 * are rewritten when rules match. With no matching rules the message is forwarded
 * byte-identical.</p>
 */
public final class SqlServerResultSetMaskingInterceptor implements MessageInterceptor {

    private static final Logger log = LoggerFactory.getLogger(SqlServerResultSetMaskingInterceptor.class);
    private static final TdsFrameCodec FRAME_CODEC = new TdsFrameCodec();
    /** Max payload per TDS packet (uint16 length includes 8-byte header). */
    private static final int MAX_PAYLOAD = TdsFrameCodec.MAX_PACKET_LENGTH - TdsFrameCodec.HEADER_LENGTH;

    private final SqlServerDatabaseEventExtractor extractor;
    private final MaskingEngine engine;

    public SqlServerResultSetMaskingInterceptor(SqlServerDatabaseEventExtractor extractor, MaskingEngine engine) {
        this.extractor = Objects.requireNonNull(extractor, "extractor");
        this.engine = Objects.requireNonNull(engine, "engine");
    }

    @Override
    public InterceptorPhase phase() {
        return InterceptorPhase.REWRITE;
    }

    @Override
    public MessageBounder messageBounder(TrafficDirection direction) {
        return direction == TrafficDirection.TARGET_TO_CLIENT
                ? extractor.messageBounder(direction)
                : null;
    }

    @Override
    public TrafficDecision intercept(WireMessage message) {
        if (message.direction() != TrafficDirection.TARGET_TO_CLIENT) {
            return TrafficDecision.forward(message);
        }
        if (!extractor.lastTargetMessageContainedRow()) {
            return TrafficDecision.forward(message);
        }
        List<TdsColumnDefinition> columns = extractor.currentTdsColumns();
        if (columns.isEmpty()) {
            return refuse(message, "COLMETADATA was not fully observed before ROW");
        }
        byte[] payload = SqlServerDatabaseEventExtractor.concatenatePayloads(
                message.originalBytes(), message.originalOffset(), message.originalLength());
        Optional<byte[]> rewritten = rewriteTabularPayload(payload, columns);
        if (rewritten.isEmpty()) {
            return refuse(message, "tabular ROW rewrite failed (unsupported type or encode error)");
        }
        if (rewritten.get() == payload || java.util.Arrays.equals(rewritten.get(), payload)) {
            return TrafficDecision.forward(message);
        }
        byte[] frames = framePayload(rewritten.get());
        return TrafficDecision.forward(message.withReplacement(frames));
    }

    private Optional<byte[]> rewriteTabularPayload(byte[] payload, List<TdsColumnDefinition> columns) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(payload.length);
        int cursor = 0;
        boolean mutated = false;
        while (cursor < payload.length) {
            int token = payload[cursor] & 0xFF;
            if (token == TdsTokenType.ROW.getCode()) {
                Optional<TdsRowCodec.ParsedRow> parsed = TdsRowCodec.tryParse(payload, cursor, columns);
                if (parsed.isEmpty()) {
                    return Optional.empty();
                }
                try {
                    List<MaskedValue> masked = applyRules(columns, parsed.get().values());
                    if (!sameValues(parsed.get().values(), masked)) {
                        mutated = true;
                        byte[] row = TdsRowCodec.encode(columns, masked);
                        out.write(row, 0, row.length);
                    } else {
                        out.write(payload, cursor, parsed.get().wireLength());
                    }
                } catch (MaskingException | IllegalArgumentException e) {
                    log.debug("SQL Server ROW mask refused: {}", e.getMessage());
                    return Optional.empty();
                }
                cursor += parsed.get().wireLength();
                continue;
            }
            if (token == TdsTokenType.NBCROW.getCode()) {
                Optional<TdsRowCodec.ParsedRow> parsed = TdsRowCodec.tryParseNbc(payload, cursor, columns);
                if (parsed.isEmpty()) {
                    return Optional.empty();
                }
                try {
                    List<MaskedValue> masked = applyRules(columns, parsed.get().values());
                    if (!sameValues(parsed.get().values(), masked)) {
                        mutated = true;
                        byte[] row = TdsRowCodec.encodeNbc(columns, masked);
                        out.write(row, 0, row.length);
                    } else {
                        out.write(payload, cursor, parsed.get().wireLength());
                    }
                } catch (MaskingException | IllegalArgumentException e) {
                    log.debug("SQL Server NBCROW mask refused: {}", e.getMessage());
                    return Optional.empty();
                }
                cursor += parsed.get().wireLength();
                continue;
            }
            Optional<Integer> skip = skipToken(payload, cursor);
            if (skip.isEmpty()) {
                // Unknown / truncated: copy remainder unchanged only when no rule applies.
                if (anyRuleApplies(columns)) {
                    return Optional.empty();
                }
                out.write(payload, cursor, payload.length - cursor);
                break;
            }
            int len = skip.get();
            out.write(payload, cursor, len);
            cursor += len;
        }
        return Optional.of(mutated ? out.toByteArray() : payload);
    }

    private List<MaskedValue> applyRules(List<TdsColumnDefinition> columns, List<MaskedValue> originals)
            throws MaskingException {
        List<MaskedValue> masked = new ArrayList<>(originals.size());
        for (int i = 0; i < columns.size(); i++) {
            TdsColumnDefinition column = columns.get(i);
            ColumnMetadata meta = column.toColumnMetadata();
            MaskingRule rule = engine.ruleFor(meta).orElse(null);
            MaskedValue original = originals.get(i);
            if (rule == null) {
                masked.add(original);
                continue;
            }
            // Rules operate on UTF-8 text / uuid strings; convert from TDS wire first.
            MaskedValue forRule = toRuleValue(column, original);
            MaskedValue replacement = engine.apply(rule, meta, forRule);
            if (replacement.isNull()) {
                if (!column.supportsNullRewrite()) {
                    throw new MaskingException("NULL mask not representable for column " + column.name());
                }
                masked.add(MaskedValue.ofNull());
                continue;
            }
            if (!column.supportsNonNullRewrite()) {
                throw new MaskingException("non-null mask not representable for column "
                        + column.name() + " type " + column.type().typeName());
            }
            // Keep MaskedValue as UTF-8 text / uuid string; TdsRowCodec.encode converts to wire.
            masked.add(replacement);
        }
        return masked;
    }

    /**
     * Converts a TDS wire value into the UTF-8 / uuid-string form masking rules expect.
     */
    static MaskedValue toRuleValue(TdsColumnDefinition column, MaskedValue wire) {
        if (wire.isNull()) {
            return wire;
        }
        TdsTypeCode type = column.type();
        if (type.isUnicodeText()) {
            String text = new String(wire.bytes(), StandardCharsets.UTF_16LE);
            return MaskedValue.ofText(text, StandardCharsets.UTF_8);
        }
        if (type == TdsTypeCode.BIGVARCHR || type == TdsTypeCode.BIGCHAR) {
            // Treat as UTF-8 for ASCII-safe masks (same as encode path).
            return MaskedValue.ofText(new String(wire.bytes(), StandardCharsets.UTF_8), StandardCharsets.UTF_8);
        }
        if (type == TdsTypeCode.GUIDTYPE) {
            return MaskedValue.ofText(decodeGuid(wire.bytes()), StandardCharsets.UTF_8);
        }
        if (TdsBinaryValues.isEncodable(type)) {
            return MaskedValue.ofText(TdsBinaryValues.decodeToText(column, wire.bytes()), StandardCharsets.UTF_8);
        }
        return wire;
    }

    static String decodeGuid(byte[] bytes) {
        if (bytes == null || bytes.length != 16) {
            throw new IllegalArgumentException("GUID must be 16 bytes");
        }
        ByteBuffer le = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        long msb = ((long) le.getInt()) << 32;
        msb |= ((long) le.getShort() & 0xFFFFL) << 16;
        msb |= ((long) le.getShort() & 0xFFFFL);
        byte[] rest = new byte[8];
        le.get(rest);
        long lsb = ByteBuffer.wrap(rest).order(ByteOrder.BIG_ENDIAN).getLong();
        return new UUID(msb, lsb).toString();
    }

    private boolean anyRuleApplies(List<TdsColumnDefinition> columns) {
        for (TdsColumnDefinition column : columns) {
            if (engine.ruleFor(column.toColumnMetadata()).isPresent()) {
                return true;
            }
        }
        return false;
    }

    private static boolean sameValues(List<MaskedValue> a, List<MaskedValue> b) {
        if (a.size() != b.size()) {
            return false;
        }
        for (int i = 0; i < a.size(); i++) {
            MaskedValue left = a.get(i);
            MaskedValue right = b.get(i);
            if (left.isNull() != right.isNull()) {
                return false;
            }
            if (!left.isNull() && !java.util.Arrays.equals(left.bytes(), right.bytes())) {
                return false;
            }
        }
        return true;
    }

    /**
     * Skips one token when we can determine its length without full semantics.
     * Returns empty for ROW/NBCROW (handled elsewhere) or unknown tokens.
     */
    static Optional<Integer> skipToken(byte[] payload, int offset) {
        if (offset >= payload.length) {
            return Optional.empty();
        }
        int token = payload[offset] & 0xFF;
        if (token == TdsTokenType.ROW.getCode() || token == TdsTokenType.NBCROW.getCode()) {
            return Optional.empty();
        }
        if (token == TdsTokenType.DONE.getCode()
                || token == TdsTokenType.DONEPROC.getCode()
                || token == TdsTokenType.DONEINPROC.getCode()) {
            if (offset + TdsDoneToken.WIRE_LENGTH > payload.length) {
                return Optional.empty();
            }
            return Optional.of(TdsDoneToken.WIRE_LENGTH);
        }
        if (token == TdsTokenType.ERROR.getCode() || token == TdsTokenType.INFO.getCode()) {
            if (offset + 3 > payload.length) {
                return Optional.empty();
            }
            int len = TdsColMetadata.u16le(payload, offset + 1);
            int total = 1 + 2 + len;
            if (offset + total > payload.length) {
                return Optional.empty();
            }
            return Optional.of(total);
        }
        if (token == TdsTokenType.COLMETADATA.getCode()) {
            Optional<TdsColMetadata.Parsed> parsed = TdsColMetadata.tryParse(payload, offset);
            return parsed.map(TdsColMetadata.Parsed::wireLength);
        }
        if (token == TdsTokenType.ENVCHANGE.getCode()) {
            if (offset + 3 > payload.length) {
                return Optional.empty();
            }
            int len = TdsColMetadata.u16le(payload, offset + 1);
            int total = 1 + 2 + len;
            if (offset + total > payload.length) {
                return Optional.empty();
            }
            return Optional.of(total);
        }
        return Optional.empty();
    }

    static byte[] framePayload(byte[] payload) {
        if (payload.length <= MAX_PAYLOAD) {
            return FRAME_CODEC.packet(TdsPacketType.TABULAR_RESULT.getCode(), payload, 1);
        }
        ByteArrayOutputStream frames = new ByteArrayOutputStream();
        int offset = 0;
        int packetId = 1;
        while (offset < payload.length) {
            int chunk = Math.min(MAX_PAYLOAD, payload.length - offset);
            boolean eom = offset + chunk >= payload.length;
            byte[] part = new byte[chunk];
            System.arraycopy(payload, offset, part, 0, chunk);
            byte[] packet = FRAME_CODEC.packet(TdsPacketType.TABULAR_RESULT.getCode(), part, packetId++);
            if (!eom) {
                // Clear EOM on non-final packets.
                packet[1] = (byte) (packet[1] & ~TdsPacketStatus.EOM);
            }
            frames.write(packet, 0, packet.length);
            offset += chunk;
        }
        return frames.toByteArray();
    }

    private TrafficDecision refuse(WireMessage message, String reason) {
        log.warn("Refusing to forward a TDS result set the gateway cannot mask: {}", reason);
        return TrafficDecision.deny(message);
    }
}
