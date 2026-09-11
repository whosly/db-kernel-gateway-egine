package com.whosly.gateway.masking;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MaskingEngineTest {

    @Test
    void returnsTheOriginalValueWhenNoRuleMatches() {
        MaskingEngine engine = new MaskingEngine(new MaskingRuleRegistry(List.of(
                new NullingRule("null-email", 0, ColumnSelector.named("email")))));
        MaskedValue original = MaskedValue.ofText("42", StandardCharsets.UTF_8);

        MaskedValue result = engine.apply(ColumnMetadata.text("order_id"), original);

        assertThat(result).isSameAs(original);
        assertThat(engine.isActive()).isTrue();
    }

    @Test
    void refusesToMaskABinaryFormattedColumn() {
        MaskingEngine engine = new MaskingEngine(new MaskingRuleRegistry(List.of(
                new NullingRule("null-all", 0, ColumnSelector.all()))));
        ColumnMetadata binary = new ColumnMetadata("payload", Optional.empty(), "bytea",
                ColumnMetadata.Category.BINARY, ColumnMetadata.ValueFormat.BINARY, true);

        assertThatThrownBy(() -> engine.apply(binary, MaskedValue.of(new byte[]{1})))
                .isInstanceOf(MaskingException.class)
                .hasMessageContaining("Binary result format");
    }

    @Test
    void refusesARuleThatCannotProduceTheColumnCategory() {
        MaskingRule textOnly = new MaskingRule() {
            @Override
            public String name() {
                return "text-only";
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
                return MaskedValue.ofText("x", StandardCharsets.UTF_8);
            }
        };
        MaskingEngine engine = new MaskingEngine(new MaskingRuleRegistry(List.of(textOnly)));
        ColumnMetadata numeric = new ColumnMetadata("age", Optional.empty(), "int4",
                ColumnMetadata.Category.NUMERIC, ColumnMetadata.ValueFormat.TEXT, false);

        assertThatThrownBy(() -> engine.apply(numeric, MaskedValue.ofText("42", StandardCharsets.UTF_8)))
                .isInstanceOf(MaskingException.class)
                .hasMessageContaining("cannot produce a valid NUMERIC");
    }

    @Test
    void wrapsARuleFailureInsteadOfForwardingTheOriginal() {
        MaskingEngine engine = new MaskingEngine(new MaskingRuleRegistry(List.of(brokenRule())));

        assertThatThrownBy(() -> engine.apply(ColumnMetadata.text("email"),
                MaskedValue.ofText("a@b.com", StandardCharsets.UTF_8)))
                .isInstanceOf(MaskingException.class)
                .hasMessageContaining("broken");
    }

    @Test
    void refusesARuleThatReturnsNoValue() {
        MaskingRule silent = new MaskingRule() {
            @Override
            public String name() {
                return "silent";
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
                return null;
            }
        };
        MaskingEngine engine = new MaskingEngine(new MaskingRuleRegistry(List.of(silent)));

        assertThatThrownBy(() -> engine.apply(ColumnMetadata.text("email"),
                MaskedValue.ofText("a@b.com", StandardCharsets.UTF_8)))
                .isInstanceOf(MaskingException.class)
                .hasMessageContaining("returned no value");
    }

    private static MaskingRule brokenRule() {
        return new MaskingRule() {
            @Override
            public String name() {
                return "broken";
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
                throw new IllegalStateException("masker unavailable");
            }
        };
    }
}
