package com.whosly.gateway.masking;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MaskingRuleRegistryTest {

    @Test
    void masksNothingWhenNoRuleIsRegistered() {
        MaskingRuleRegistry registry = new MaskingRuleRegistry(List.of());

        assertThat(registry.isEmpty()).isTrue();
        assertThat(registry.rules()).isEmpty();
        assertThat(registry.resolve(ColumnMetadata.text("email"))).isEmpty();
    }

    @Test
    void resolvesTheHighestPriorityMatchingRule() {
        MaskingRule low = custom("low", 1);
        MaskingRule high = custom("high", 9);
        MaskingRuleRegistry registry = new MaskingRuleRegistry(List.of(low, high));

        assertThat(registry.rules()).extracting(MaskingRule::name).containsExactly("high", "low");
        assertThat(registry.resolve(ColumnMetadata.text("email"))).contains(high);
    }

    @Test
    void rejectsTwoRulesSharingTheHighestPriority() {
        MaskingRuleRegistry registry = new MaskingRuleRegistry(List.of(custom("first", 5), custom("second", 5)));

        assertThatThrownBy(() -> registry.resolve(ColumnMetadata.text("email")))
                .isInstanceOf(MaskingException.class)
                .hasMessageContaining("first")
                .hasMessageContaining("second");
        assertThat(registry.getConflictCount()).isEqualTo(1);
    }

    @Test
    void rejectsDuplicateRuleNamesAtRegistration() {
        assertThatThrownBy(() -> new MaskingRuleRegistry(List.of(custom("same", 1), custom("same", 2))))
                .isInstanceOf(MaskingException.class)
                .hasMessageContaining("same");
    }

    @Test
    void acceptsACustomRuleWithoutAnyEngineChange() {
        // Injection is the whole mechanism: register a rule, nothing else changes.
        MaskingRule upperCase = new MaskingRule() {
            @Override
            public String name() {
                return "upper-case";
            }

            @Override
            public Set<ColumnMetadata.Category> supportedCategories() {
                return Set.of(ColumnMetadata.Category.TEXT);
            }

            @Override
            public boolean matches(ColumnMetadata column) {
                return column.name().equalsIgnoreCase("email");
            }

            @Override
            public MaskedValue mask(ColumnMetadata column, MaskedValue original) {
                return MaskedValue.ofText(original.asText(StandardCharsets.UTF_8).toUpperCase(Locale.ROOT),
                        StandardCharsets.UTF_8);
            }
        };
        MaskingEngine engine = new MaskingEngine(new MaskingRuleRegistry(List.of(upperCase)));

        MaskedValue masked = engine.apply(ColumnMetadata.text("email"),
                MaskedValue.ofText("a@b.com", StandardCharsets.UTF_8));

        assertThat(masked.asText(StandardCharsets.UTF_8)).isEqualTo("A@B.COM");
    }

    private static MaskingRule custom(String name, int priority) {
        return new MaskingRule() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public int priority() {
                return priority;
            }

            @Override
            public Set<ColumnMetadata.Category> supportedCategories() {
                return Set.of(ColumnMetadata.Category.TEXT);
            }

            @Override
            public boolean matches(ColumnMetadata column) {
                return true;
            }

            @Override
            public MaskedValue mask(ColumnMetadata column, MaskedValue original) {
                return MaskedValue.ofText("masked", StandardCharsets.UTF_8);
            }
        };
    }
}
