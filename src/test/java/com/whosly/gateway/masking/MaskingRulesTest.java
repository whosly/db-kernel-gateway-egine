package com.whosly.gateway.masking;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class MaskingRulesTest {

    private static final java.nio.charset.Charset UTF8 = StandardCharsets.UTF_8;

    @Test
    void nullingRuleReplacesTheValueAndSkipsNotNullColumns() {
        NullingRule rule = new NullingRule("null-email", 10, ColumnSelector.named("email"));
        ColumnMetadata notNull = new ColumnMetadata("email", Optional.empty(), "text",
                ColumnMetadata.Category.TEXT, ColumnMetadata.ValueFormat.TEXT, false);

        assertThat(rule.matches(ColumnMetadata.text("email"))).isTrue();
        assertThat(rule.matches(notNull)).isFalse();
        assertThat(rule.mask(ColumnMetadata.text("email"),
                MaskedValue.ofText("a@b.com", UTF8)).isNull()).isTrue();
    }

    @Test
    void fixedValueRuleClaimsTextNumericTemporalAndUuid() {
        FixedValueRule rule = new FixedValueRule("fixed-phone", 5, ColumnSelector.named("phone"), "0");
        ColumnMetadata numeric = new ColumnMetadata("phone", Optional.empty(), "int4",
                ColumnMetadata.Category.NUMERIC, ColumnMetadata.ValueFormat.TEXT, true);
        ColumnMetadata temporal = new ColumnMetadata("phone", Optional.empty(), "date",
                ColumnMetadata.Category.TEMPORAL, ColumnMetadata.ValueFormat.TEXT, true);
        ColumnMetadata uuid = new ColumnMetadata("phone", Optional.empty(), "uuid",
                ColumnMetadata.Category.UUID, ColumnMetadata.ValueFormat.TEXT, true);
        ColumnMetadata bool = new ColumnMetadata("phone", Optional.empty(), "bool",
                ColumnMetadata.Category.BOOLEAN, ColumnMetadata.ValueFormat.TEXT, true);

        assertThat(rule.matches(ColumnMetadata.text("phone"))).isTrue();
        assertThat(rule.matches(numeric)).isTrue();
        assertThat(rule.matches(temporal)).isTrue();
        assertThat(rule.matches(uuid)).isTrue();
        assertThat(rule.matches(bool)).isFalse();
        assertThat(rule.mask(numeric, MaskedValue.ofText("13800000000", UTF8)).asText(UTF8)).isEqualTo("0");
    }

    @Test
    void partialAndHashRulesClaimUuidWhileKeepingAParseableShape() {
        ColumnMetadata uuid = new ColumnMetadata("id", Optional.empty(), "uuid",
                ColumnMetadata.Category.UUID, ColumnMetadata.ValueFormat.TEXT, true);
        PartialMaskRule partial = new PartialMaskRule("partial-id", 5, ColumnSelector.named("id"), 4, 4);
        HashingRule hash = new HashingRule("hash-id", 5, ColumnSelector.named("id"), 16, "pepper");

        assertThat(partial.matches(uuid)).isTrue();
        assertThat(hash.matches(uuid)).isTrue();
        // keepPrefix=4 keepSuffix=4 on 32 hex digits → 550e + 24 zeros + 0000, hyphens restored.
        String masked = partial.mask(uuid,
                MaskedValue.ofText("550e8400-e29b-41d4-a716-446655440000", UTF8)).asText(UTF8);
        assertThat(masked).isEqualTo("550e0000-0000-0000-0000-000000000000");
        String hashed = hash.mask(uuid,
                MaskedValue.ofText("550e8400-e29b-41d4-a716-446655440000", UTF8))
                .asText(StandardCharsets.US_ASCII);
        assertThat(java.util.UUID.fromString(hashed)).isNotNull();
        assertThat(hashed).isEqualTo(hash.mask(uuid,
                MaskedValue.ofText("550e8400-e29b-41d4-a716-446655440000", UTF8))
                .asText(StandardCharsets.US_ASCII));
    }

    @Test
    void partialMaskRuleKeepsTheConfiguredEdges() {
        PartialMaskRule rule = new PartialMaskRule("card", 5, ColumnSelector.named("card_no"), 4, 4);
        ColumnMetadata column = ColumnMetadata.text("card_no");

        assertThat(rule.mask(column, MaskedValue.ofText("6222021234561234", UTF8)).asText(UTF8))
                .isEqualTo("6222********1234");
        // A value too short to keep both edges is masked entirely.
        assertThat(rule.mask(column, MaskedValue.ofText("1234", UTF8)).asText(UTF8)).isEqualTo("****");
        // NULL stays NULL: there is nothing to mask.
        assertThat(rule.mask(column, MaskedValue.ofNull()).isNull()).isTrue();
    }

    @Test
    void hashingRuleIsDeterministicAndSaltChangesTheDigest() {
        ColumnMetadata column = ColumnMetadata.text("email");
        HashingRule rule = new HashingRule("hash-email", 5, ColumnSelector.named("email"), 16, "pepper");
        HashingRule otherSalt = new HashingRule("hash-email-2", 5, ColumnSelector.named("email"), 16, "other");

        String first = rule.mask(column, MaskedValue.ofText("a@b.com", UTF8)).asText(StandardCharsets.US_ASCII);
        String second = rule.mask(column, MaskedValue.ofText("a@b.com", UTF8)).asText(StandardCharsets.US_ASCII);
        String different = otherSalt.mask(column, MaskedValue.ofText("a@b.com", UTF8))
                .asText(StandardCharsets.US_ASCII);

        assertThat(first).hasSize(16).isEqualTo(second).isNotEqualTo(different);
    }

    @Test
    void encryptingRuleIsReversibleAndNotDeterministic() {
        MaskingCipher cipher = new MaskingCipher(MaskingKeyProvider.ofBase64("k1",
                Base64.getEncoder().encodeToString(new byte[32])));
        EncryptingRule rule = new EncryptingRule("encrypt-email", 5, ColumnSelector.named("email"), cipher, "k1");
        ColumnMetadata column = ColumnMetadata.text("email");

        String first = rule.mask(column, MaskedValue.ofText("a@b.com", UTF8)).asText(StandardCharsets.US_ASCII);
        String second = rule.mask(column, MaskedValue.ofText("a@b.com", UTF8)).asText(StandardCharsets.US_ASCII);

        assertThat(first).isNotEqualTo(second);
        assertThat(cipher.decrypt("k1", first)).isEqualTo("a@b.com");
        assertThat(cipher.decrypt("k1", second)).isEqualTo("a@b.com");
    }
}
