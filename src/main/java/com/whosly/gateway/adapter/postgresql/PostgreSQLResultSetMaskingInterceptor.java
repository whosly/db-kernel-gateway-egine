package com.whosly.gateway.adapter.postgresql;

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
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Applies masking rules to the rows of a PostgreSQL result set.
 *
 * <p>The PostgreSQL counterpart of the MySQL interceptor, with the same contract:
 * only {@code DataRow} messages of the backend direction are rewritten, only the
 * columns a rule claims change, and everything the gateway cannot do safely is
 * refused instead of approximated (rule 8.2).</p>
 *
 * <p>Three things differ from MySQL and are worth stating:</p>
 * <ul>
 *   <li>a {@code DataRow} is recognised by its own type byte, taken from the message
 *       being rewritten rather than from what the observer saw last: the message is
 *       authoritative about itself, so no phase can be mis-attributed;</li>
 *   <li>a row carries its own field count, which is checked against the
 *       {@code RowDescription} — PostgreSQL gives no other way to tell that a value
 *       belongs to the column a rule claims;</li>
 *   <li>a binary-format column is still maskable: a row is a flat list of
 *       length-prefixed values in both formats, so only the value encoding differs —
 *       NULL is valid for every type, and a non-null value is encoded for its type by
 *       {@link PostgreSQLBinaryValues}, or refused when that type cannot be
 *       reproduced.</li>
 * </ul>
 *
 * @author yueny09@163.com codealy
 * @since 2026-07-02
 */
public final class PostgreSQLResultSetMaskingInterceptor implements MessageInterceptor {

    private static final Logger log = LoggerFactory.getLogger(PostgreSQLResultSetMaskingInterceptor.class);
    private static final PostgreSQLFrameCodec FRAME_CODEC = new PostgreSQLFrameCodec();
    private static final char DATA_ROW_CODE = 'D';

    private final PostgreSQLDatabaseEventExtractor extractor;
    private final MaskingEngine engine;
    /**
     * Columns and their resolved rules for the result set being rewritten.
     *
     * <p>Only the backend direction touches these: the other direction returns
     * before any of it is read.</p>
     */
    private List<ColumnMetadata> resolvedColumns = List.of();
    private List<MaskingRule> resolvedRules = List.of();

    public PostgreSQLResultSetMaskingInterceptor(PostgreSQLDatabaseEventExtractor extractor,
                                                 MaskingEngine engine) {
        this.extractor = Objects.requireNonNull(extractor, "extractor must not be null");
        this.engine = Objects.requireNonNull(engine, "engine must not be null");
    }

    @Override
    public InterceptorPhase phase() {
        return InterceptorPhase.REWRITE;
    }

    /**
     * Asks for whole messages only in the direction that is rewritten, so the
     * frontend direction keeps being forwarded as it arrives (rule 2.10).
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
                || !isDataRow(message)) {
            // RowDescription, CommandComplete, ReadyForQuery and frontend messages
            // carry no values to mask.
            return TrafficDecision.forward(message);
        }

        List<ColumnMetadata> columns = extractor.currentResultSetColumns();
        if (columns.isEmpty() || columns.size() != extractor.currentResultSetColumnCount()) {
            return refuse(message, "RowDescription was not fully observed (" + columns.size()
                    + " of " + extractor.currentResultSetColumnCount() + ")");
        }

        try {
            List<MaskingRule> rules = rulesFor(columns);
            if (rules.stream().allMatch(Objects::isNull)) {
                return TrafficDecision.forward(message);
            }

            int payloadLength = message.originalLength() - PostgreSQLFrameCodec.TYPED_HEADER_LENGTH;
            int declaredLength = PostgreSQLFrameCodec.readInt4(message.originalBytes(),
                    message.originalOffset() + 1, message.originalOffset() + message.originalLength());
            if (payloadLength < 2 || declaredLength != payloadLength + PostgreSQLFrameCodec.UNTYPED_HEADER_LENGTH) {
                return refuse(message, "message does not contain exactly one PostgreSQL message");
            }

            Optional<List<byte[]>> parsed = PostgreSQLDataRow.parse(message.originalBytes(),
                    message.originalOffset() + PostgreSQLFrameCodec.TYPED_HEADER_LENGTH, payloadLength);
            if (parsed.isEmpty()) {
                return refuse(message, "row payload is not a valid DataRow");
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
            return TrafficDecision.forward(
                    message.withReplacement(FRAME_CODEC.packet(DATA_ROW_CODE, PostgreSQLDataRow.encode(masked))));
        } catch (MaskingException e) {
            return refuse(message, e.getMessage());
        }
    }

    /** True when this message is a {@code DataRow}, judged from the message itself. */
    private static boolean isDataRow(WireMessage message) {
        if (message.originalLength() < PostgreSQLFrameCodec.TYPED_HEADER_LENGTH) {
            return false;
        }
        return message.originalBytes()[message.originalOffset()] == DATA_ROW_CODE;
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
            MaskedValue original = values.get(index) == null
                    ? MaskedValue.ofNull()
                    : MaskedValue.of(values.get(index));
            MaskedValue replacement = engine.apply(rule, columns.get(index), original);
            if (replacement.isNull() == original.isNull()
                    && Arrays.equals(replacement.bytes(), original.bytes())) {
                continue;
            }
            if (masked == null) {
                masked = new ArrayList<>(values);
            }
            /*
             * NULL needs no encoding — the protocol's NULL marker is valid for every
             * type, which is what makes nulling a binary column work. A non-null value
             * is encoded for the column's type, and a type with no known binary encoding
             * is refused rather than filled with bytes the client cannot parse.
             */
            masked.set(index, replacement.isNull()
                    ? null
                    : PostgreSQLBinaryValues.encode(columns.get(index), replacement));
        }
        return masked;
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
            // Not List.copyOf: a null entry means "no rule claims this column".
            resolvedRules = Collections.unmodifiableList(rules);
        }
        return resolvedRules;
    }

    /**
     * Rule that claims a column, or {@code null} when none does.
     *
     * <p>Deliberately not filtered by the column's wire format: NULL is representable
     * for every type, so whether a mask can be applied is decided per value, when the
     * replacement is known.</p>
     */
    private MaskingRule engineRule(ColumnMetadata column) {
        return engine.ruleFor(column).orElse(null);
    }

    private TrafficDecision refuse(WireMessage message, String reason) {
        log.warn("Refusing to forward a result set the gateway cannot mask as configured: {}", reason);
        return TrafficDecision.deny(message);
    }
}
