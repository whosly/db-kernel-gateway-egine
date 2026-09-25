package com.whosly.gateway.console.masking;

import com.whosly.gateway.console.persist.MaskingRuleRecord;
import com.whosly.gateway.masking.ColumnMetadata;
import com.whosly.gateway.masking.EncryptingRule;
import com.whosly.gateway.masking.FixedValueRule;
import com.whosly.gateway.masking.HashingRule;
import com.whosly.gateway.masking.MaskedValue;
import com.whosly.gateway.masking.MaskingCipher;
import com.whosly.gateway.masking.MaskingKeyProvider;
import com.whosly.gateway.masking.MaskingRule;
import com.whosly.gateway.masking.NullingRule;
import com.whosly.gateway.masking.PartialMaskRule;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InstanceMaskingRuleCompilerTest {

    @Test
    void mapsStrategiesToRuleClasses() {
        InstanceMaskingRuleCompiler compiler = new InstanceMaskingRuleCompiler(Optional.empty(), "default");
        assertThat(compiler.compile(row("null", "email", null))).isInstanceOf(NullingRule.class);
        assertThat(compiler.compile(row("fixed", "email", "X"))).isInstanceOf(FixedValueRule.class);
        assertThat(compiler.compile(partial())).isInstanceOf(PartialMaskRule.class);
        assertThat(compiler.compile(hash())).isInstanceOf(HashingRule.class);
    }

    @Test
    void encryptRequiresConfiguredKey() {
        InstanceMaskingRuleCompiler noKey = new InstanceMaskingRuleCompiler(Optional.empty(), "default");
        MaskingRuleRecord encrypt = new MaskingRuleRecord(
                "r1", "inst", "enc", "encrypt", 1,
                "secret", null, null, null, null, null, null,
                true, null, null);
        assertThatThrownBy(() -> noKey.validateForPersist(encrypt))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("encrypt")
                .hasMessageContaining("脱敏密钥");

        byte[] key = new byte[32];
        for (int i = 0; i < key.length; i++) {
            key[i] = (byte) i;
        }
        MaskingCipher cipher = new MaskingCipher(
                MaskingKeyProvider.ofBase64("default", Base64.getEncoder().encodeToString(key)));
        InstanceMaskingRuleCompiler withKey = new InstanceMaskingRuleCompiler(cipher, "default");
        MaskingRule rule = withKey.compile(encrypt);
        assertThat(rule).isInstanceOf(EncryptingRule.class);
        assertThat(rule.priority()).isEqualTo(1 + InstanceMaskingRuleCompiler.INSTANCE_PRIORITY_BOOST);
    }

    @Test
    void selectorUsesColumnTableAndPattern() {
        InstanceMaskingRuleCompiler compiler = new InstanceMaskingRuleCompiler(Optional.empty(), "default");
        MaskingRule exact = compiler.compile(new MaskingRuleRecord(
                "r", "i", "n", "null", 0,
                "Email", "Users", null, null, null, null, null,
                true, null, null));
        ColumnMetadata match = new ColumnMetadata(
                "email", Optional.of("users"), "text",
                ColumnMetadata.Category.TEXT, ColumnMetadata.ValueFormat.TEXT, true);
        ColumnMetadata wrongTable = new ColumnMetadata(
                "email", Optional.of("orders"), "text",
                ColumnMetadata.Category.TEXT, ColumnMetadata.ValueFormat.TEXT, true);
        assertThat(exact.matches(match)).isTrue();
        assertThat(exact.matches(wrongTable)).isFalse();

        MaskingRule pattern = compiler.compile(new MaskingRuleRecord(
                "r2", "i", "n2", "fixed", 0,
                null, null, ".*phone.*", "X", null, null, null,
                true, null, null));
        assertThat(pattern.matches(new ColumnMetadata(
                "user_phone", Optional.empty(), "text",
                ColumnMetadata.Category.TEXT, ColumnMetadata.ValueFormat.TEXT, true))).isTrue();
    }

    @Test
    void fixedValueMasksText() {
        InstanceMaskingRuleCompiler compiler = new InstanceMaskingRuleCompiler(Optional.empty(), "default");
        MaskingRule rule = compiler.compile(row("fixed", "email", "REDACTED"));
        MaskedValue out = rule.mask(
                ColumnMetadata.text("email"),
                MaskedValue.ofText("alice@example.com", StandardCharsets.UTF_8));
        assertThat(out.asText(StandardCharsets.UTF_8)).isEqualTo("REDACTED");
    }

    private static MaskingRuleRecord row(String strategy, String column, String fixed) {
        return new MaskingRuleRecord(
                "id-" + strategy, "inst", "rule-" + strategy, strategy, 1,
                column, null, null, fixed, null, null, null,
                true, null, null);
    }

    private static MaskingRuleRecord partial() {
        return new MaskingRuleRecord(
                "id-p", "inst", "partial", "partial", 1,
                "card", null, null, null, 4, 4, null,
                true, null, null);
    }

    private static MaskingRuleRecord hash() {
        return new MaskingRuleRecord(
                "id-h", "inst", "hash", "hash", 1,
                "email", null, null, null, null, null, 16,
                true, null, null);
    }

    @Test
    void encryptRoundTripUsesActiveKey() {
        byte[] key = new byte[32];
        for (int i = 0; i < key.length; i++) {
            key[i] = (byte) (i + 9);
        }
        MaskingCipher cipher = new MaskingCipher(
                MaskingKeyProvider.ofBase64("default", Base64.getEncoder().encodeToString(key)));
        InstanceMaskingRuleCompiler withKey = new InstanceMaskingRuleCompiler(cipher, "default");
        MaskingRuleRecord encrypt = new MaskingRuleRecord(
                "r1", "inst", "enc", "encrypt", 1,
                "secret", null, null, null, null, null, null,
                true, null, null);
        EncryptingRule rule = (EncryptingRule) withKey.compile(encrypt);
        String out = rule.mask(
                ColumnMetadata.text("secret"),
                MaskedValue.ofText("plain-secret", StandardCharsets.UTF_8))
                .asText(StandardCharsets.US_ASCII);
        assertThat(out).startsWith(MaskingCipher.WIRE_PREFIX);
        assertThat(cipher.decrypt(out)).isEqualTo("plain-secret");
    }
}
