package com.whosly.gateway.adapter.mysql;

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

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Applies masking rules to the rows of a MySQL result set, text protocol and binary
 * protocol alike.
 *
 * <p>This is the interceptor that finally makes {@code MaskingRule} beans take
 * effect on the data path. It rewrites only result-set rows of the direction that
 * carries data back to the client, and it is installed only when at least one rule
 * is registered — with no rules the pipeline is exactly what it was before, byte
 * for byte.</p>
 *
 * <p>Everything it cannot do safely is refused rather than approximated
 * (rule 8.2):</p>
 * <ul>
 *   <li>a result set whose column definitions could not all be parsed is refused,
 *       because a value could not be attributed to the column a rule claims;</li>
 *   <li>a non-null masked value for a column whose binary layout is not reproduced —
 *       packed decimals, temporal types and anything unknown — is refused; a NULL mask
 *       works for every type, because it is written through the row's null bitmap;</li>
 *   <li>a binary row is parsed only when its column layouts are known and it consumes
 *       its payload exactly: a wrong width would not fail loudly, it would shift every
 *       following value into the wrong column;</li>
 *   <li>a row whose value count disagrees with the header is refused, because the
 *       values would be attributed to the wrong columns;</li>
 *   <li>a row that grows past the packet limit is refused, because the gateway must
 *       not silently split a row the client is expecting as one packet.</li>
 * </ul>
 *
 * <p>Observing and rewriting the same stream is why this class reads the column
 * metadata from the protocol layer's extractor instead of parsing packets itself:
 * the extractor already tracks the result-set phase, and a second state machine
 * would inevitably drift from it (rule 2.10).</p>
 *
 * @author yueny09@163.com codealy
 * @since 2026-07-02
 */
public final class MySQLResultSetMaskingInterceptor implements MessageInterceptor {

    private static final Logger log = LoggerFactory.getLogger(MySQLResultSetMaskingInterceptor.class);
    private static final MySQLFrameCodec FRAME_CODEC = new MySQLFrameCodec();

    private final MySQLDatabaseEventExtractor extractor;
    private final MaskingEngine engine;
    /**
     * Columns and their resolved rules for the result set being rewritten.
     *
     * <p>Only the target-to-client direction touches these: the other direction
     * returns before any of it is read.</p>
     */
    private List<ColumnMetadata> resolvedColumns = List.of();
    private List<MaskingRule> resolvedRules = List.of();

    public MySQLResultSetMaskingInterceptor(MySQLDatabaseEventExtractor extractor, MaskingEngine engine) {
        this.extractor = Objects.requireNonNull(extractor, "extractor must not be null");
        this.engine = Objects.requireNonNull(engine, "engine must not be null");
    }

    @Override
    public InterceptorPhase phase() {
        return InterceptorPhase.REWRITE;
    }

    /**
     * Asks for whole messages only in the direction that is rewritten.
     *
     * <p>The client direction is left alone on purpose: holding its bytes would add
     * the rewrite delay to the command path for no benefit, since no command is
     * ever rewritten here (rule 2.10).</p>
     */
    @Override
    public MessageBounder messageBounder(TrafficDirection direction) {
        return direction == TrafficDirection.TARGET_TO_CLIENT
                ? extractor.messageBounder(direction)
                : null;
    }

    @Override
    public TrafficDecision intercept(WireMessage message) {
        if (message.direction() != TrafficDirection.TARGET_TO_CLIENT
                || !extractor.lastResponsePacketWasResultSetRow()) {
            // Headers, terminators, OK/ERR packets and client commands carry no rows.
            return TrafficDecision.forward(message);
        }

        List<ColumnMetadata> columns = extractor.currentResultSetColumns();
        if (columns.isEmpty() || columns.size() != extractor.currentResultSetColumnCount()) {
            return refuse(message, "column definitions were not fully observed (" + columns.size()
                    + " of " + extractor.currentResultSetColumnCount() + ")");
        }

        /*
         * A MySQL result set has one wire format for all of its columns: it is decided by
         * the command, not by the column. A set that mixes the two cannot be interpreted
         * column by column, so it is refused rather than parsed by halves.
         */
        boolean binaryRow = columns.get(0).format() == ColumnMetadata.ValueFormat.BINARY;
        if (columns.stream().anyMatch(column -> column.format() != columns.get(0).format())) {
            return refuse(message, "result set mixes text and binary column formats");
        }

        try {
            List<MaskingRule> rules = rulesFor(columns);
            if (rules.stream().allMatch(Objects::isNull)) {
                // No rule claims any column of this result set, so the row is forwarded
                // untouched — and never even parsed.
                return TrafficDecision.forward(message);
            }

            /*
             * The message is one whole MySQL packet, header included, so the values
             * start after the header. The declared length is checked rather than
             * trusted: if a caller ever handed over more than one packet, the values
             * would otherwise be parsed across a message boundary.
             */
            int payloadOffset = message.originalOffset() + MySQLFrameCodec.HEADER_LENGTH;
            int payloadLength = message.originalLength() - MySQLFrameCodec.HEADER_LENGTH;
            int declaredLength = MySQLFrameCodec.payloadLength(message.originalBytes(), message.originalOffset(),
                    message.originalOffset() + message.originalLength());
            if (payloadLength < 0 || declaredLength != payloadLength) {
                return refuse(message, "message does not contain exactly one MySQL packet");
            }

            Optional<List<byte[]>> parsed = binaryRow
                    ? MySQLBinaryRow.parse(message.originalBytes(), payloadOffset, payloadLength, columns)
                    : MySQLTextRow.parse(message.originalBytes(), payloadOffset, payloadLength);
            if (parsed.isEmpty()) {
                return refuse(message, "row payload could not be parsed for this result set's column types");
            }
            List<byte[]> values = parsed.get();
            if (values.size() != columns.size()) {
                return refuse(message, "row carries " + values.size() + " value(s) for " + columns.size()
                        + " column(s)");
            }

            List<byte[]> masked = mask(values, columns, rules, binaryRow);
            if (masked == null) {
                return TrafficDecision.forward(message);
            }
            return TrafficDecision.forward(message.withReplacement(reEncode(message, masked, columns, binaryRow)));
        } catch (MaskingException e) {
            /*
             * Every masking refusal has the same answer: do not return data the
             * gateway was told to protect but could not.
             */
            return refuse(message, e.getMessage());
        }
    }

    /**
     * Masks the values a rule claims.
     *
     * @return the new values, or {@code null} when masking changed nothing
     */
    private List<byte[]> mask(List<byte[]> values, List<ColumnMetadata> columns, List<MaskingRule> rules,
                              boolean binaryRow) {
        List<byte[]> masked = null;
        for (int index = 0; index < columns.size(); index++) {
            MaskingRule rule = rules.get(index);
            if (rule == null) {
                continue;
            }
            MaskedValue original = toValue(values.get(index));
            MaskedValue replacement = engine.apply(rule, columns.get(index), original);
            if (replacement.isNull() == original.isNull()
                    && java.util.Arrays.equals(replacement.bytes(), original.bytes())) {
                continue;
            }
            if (masked == null) {
                masked = new ArrayList<>(values);
            }
            /*
             * NULL is written through the protocol's own NULL representation — the row's
             * null bitmap for a binary row, a marker byte for a text row — so it works for
             * every column type. A non-null value must be encoded for its column, and a
             * type whose binary layout is not reproduced is refused instead of filled with
             * bytes the client would fail to parse.
             */
            masked.set(index, replacement.isNull()
                    ? null
                    : encodeValue(binaryRow, columns.get(index), replacement));
        }
        return masked;
    }

    private static byte[] encodeValue(boolean binaryRow, ColumnMetadata column, MaskedValue replacement) {
        return binaryRow ? MySQLBinaryValues.encode(column, replacement) : replacement.bytes();
    }

    private byte[] reEncode(WireMessage message, List<byte[]> values, List<ColumnMetadata> columns,
                            boolean binaryRow) {
        byte[] payload = binaryRow ? MySQLBinaryRow.encode(columns, values) : MySQLTextRow.encode(values);
        if (payload.length > MySQLFrameCodec.MAX_PAYLOAD_LENGTH) {
            throw new MaskingException("Masked row no longer fits one MySQL packet: " + payload.length
                    + " bytes");
        }
        int sequenceId = MySQLFrameCodec.sequenceId(message.originalBytes(), message.originalOffset(),
                message.originalOffset() + message.originalLength());
        return FRAME_CODEC.packet(payload, sequenceId);
    }

    /**
     * Resolves the rule for each column of the current result set, once per result
     * set: resolution walks every registered rule and is therefore cached.
     */
    private List<MaskingRule> rulesFor(List<ColumnMetadata> columns) {
        if (!columns.equals(resolvedColumns)) {
            List<MaskingRule> rules = new ArrayList<>(columns.size());
            for (ColumnMetadata column : columns) {
                rules.add(engineRule(column));
            }
            resolvedColumns = List.copyOf(columns);
            /*
             * Not List.copyOf: a null entry is how this class records "no rule claims
             * this column", which List.copyOf rejects outright.
             */
            resolvedRules = Collections.unmodifiableList(rules);
        }
        return resolvedRules;
    }

    /**
     * Rule that claims a column, or {@code null} when none does.
     *
     * <p>Deliberately not filtered by the wire format: NULL is representable for every
     * type — through the row's null bitmap in a binary row — so whether a mask can be
     * applied is decided per value, when the replacement is known.</p>
     */
    private MaskingRule engineRule(ColumnMetadata column) {
        return engine.ruleFor(column).orElse(null);
    }

    private static MaskedValue toValue(byte[] value) {
        return value == null ? MaskedValue.ofNull() : MaskedValue.of(value);
    }

    private TrafficDecision refuse(WireMessage message, String reason) {
        log.warn("Refusing to forward a result set the gateway cannot mask as configured: {}", reason);
        return TrafficDecision.deny(message);
    }
}
