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
 * Applies masking rules to the rows of a MySQL text-protocol result set.
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
 *   <li>a column a rule matches but whose values are not text is refused, because
 *       this interceptor can only re-encode text values;</li>
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

            Optional<List<byte[]>> parsed = MySQLTextRow.parse(
                    message.originalBytes(), payloadOffset, payloadLength);
            if (parsed.isEmpty()) {
                return refuse(message, "row payload is not a valid text-protocol row");
            }
            List<byte[]> values = parsed.get();
            if (values.size() != columns.size()) {
                return refuse(message, "row carries " + values.size() + " value(s) for " + columns.size()
                        + " column(s)");
            }

            List<byte[]> masked = mask(values, columns, rules);
            if (masked == null) {
                return TrafficDecision.forward(message);
            }
            return TrafficDecision.forward(message.withReplacement(reEncode(message, masked)));
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
    private List<byte[]> mask(List<byte[]> values, List<ColumnMetadata> columns, List<MaskingRule> rules) {
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
            masked.set(index, replacement.isNull() ? null : replacement.bytes());
        }
        return masked;
    }

    private byte[] reEncode(WireMessage message, List<byte[]> values) {
        byte[] payload = MySQLTextRow.encode(values);
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

    private MaskingRule engineRule(ColumnMetadata column) {
        MaskingRule rule = engine.ruleFor(column).orElse(null);
        if (rule == null) {
            return null;
        }
        /*
         * A binary row (the result of an executed prepared statement) is encoded per
         * column type behind a null bitmap, and rewriting any value in it means
         * reproducing that type's binary layout. Until that codec exists, the whole
         * result set is refused instead of returning a claimed column unmasked.
         * PostgreSQL binary results are supported, because there a row is a flat list of
         * length-prefixed values and only the value encoding differs.
         */
        if (column.format() != ColumnMetadata.ValueFormat.TEXT) {
            throw new MaskingException("Column " + column.name()
                    + " is matched by rule " + rule.name()
                    + " but binary rows of prepared statements are not supported yet");
        }
        return rule;
    }

    private static MaskedValue toValue(byte[] value) {
        return value == null ? MaskedValue.ofNull() : MaskedValue.of(value);
    }

    private TrafficDecision refuse(WireMessage message, String reason) {
        log.warn("Refusing to forward a result set the gateway cannot mask as configured: {}", reason);
        return TrafficDecision.deny(message);
    }
}
