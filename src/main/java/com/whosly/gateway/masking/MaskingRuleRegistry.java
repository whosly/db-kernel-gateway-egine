package com.whosly.gateway.masking;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The set of masking rules a deployment registered.
 *
 * <p>Registration is the injection point: every {@link MaskingRule} bean in the
 * application context is collected here, so a deployment adds masking by adding
 * beans and removes it by removing them. With no rules registered the registry
 * is empty and every value passes through unchanged, which is the default: raw
 * data stays visible unless a rule explicitly claims a column.</p>
 *
 * <p>Resolution is deterministic. Rules are ordered by descending priority; when
 * two rules share the highest priority for one column the registry reports a
 * conflict instead of picking one, because guessing here means silently applying
 * the wrong protection.</p>
 *
 * @author yueny09@163.com codealy
 * @since 2026-07-02
 */
@Component
public final class MaskingRuleRegistry {

    private static final Comparator<MaskingRule> BY_PRIORITY =
            Comparator.comparingInt(MaskingRule::priority).reversed()
                    .thenComparing(MaskingRule::name);

    private final List<MaskingRule> rules;
    private final AtomicLong conflicts = new AtomicLong();

    /**
     * @param rules every {@link MaskingRule} bean; an empty list is valid and
     *              means "mask nothing"
     */
    public MaskingRuleRegistry(List<MaskingRule> rules) {
        Objects.requireNonNull(rules, "rules must not be null");
        Map<String, MaskingRule> byName = new LinkedHashMap<>();
        for (MaskingRule rule : rules) {
            Objects.requireNonNull(rule, "rule must not be null");
            MaskingRule duplicate = byName.putIfAbsent(rule.name(), rule);
            if (duplicate != null) {
                throw new MaskingException("Duplicate masking rule name: " + rule.name());
            }
        }

        List<MaskingRule> ordered = new ArrayList<>(byName.values());
        ordered.sort(BY_PRIORITY);
        this.rules = List.copyOf(ordered);
    }

    /** Registered rules, highest priority first. */
    public List<MaskingRule> rules() {
        return rules;
    }

    /** True when no rule is registered, so values pass through untouched. */
    public boolean isEmpty() {
        return rules.isEmpty();
    }

    /**
     * Resolves the single rule that applies to a column.
     *
     * @return the winning rule, or empty when no rule matches
     * @throws MaskingException when two rules share the highest priority for this
     *                          column
     */
    public Optional<MaskingRule> resolve(ColumnMetadata column) {
        Objects.requireNonNull(column, "column must not be null");
        List<MaskingRule> matching = rules.stream()
                .filter(rule -> rule.matches(column))
                .toList();
        if (matching.isEmpty()) {
            return Optional.empty();
        }

        MaskingRule winner = matching.get(0);
        List<String> samePriority = matching.stream()
                .filter(rule -> rule.priority() == winner.priority())
                .map(MaskingRule::name)
                .toList();
        if (samePriority.size() > 1) {
            conflicts.incrementAndGet();
            throw new MaskingException("Masking rules " + samePriority + " match column " + column.name()
                    + " with priority " + winner.priority() + "; resolve the conflict explicitly");
        }
        return Optional.of(winner);
    }

    /** Number of ambiguous resolutions observed; exposed for metrics. */
    public long getConflictCount() {
        return conflicts.get();
    }
}
