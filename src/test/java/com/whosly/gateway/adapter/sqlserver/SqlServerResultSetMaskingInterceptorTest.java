package com.whosly.gateway.adapter.sqlserver;

import com.whosly.gateway.adapter.protocol.RawBackedMessage;
import com.whosly.gateway.adapter.protocol.TrafficAction;
import com.whosly.gateway.adapter.protocol.TrafficDecision;
import com.whosly.gateway.adapter.protocol.TrafficDirection;
import com.whosly.gateway.masking.ColumnSelector;
import com.whosly.gateway.masking.FixedValueRule;
import com.whosly.gateway.masking.MaskingEngine;
import com.whosly.gateway.masking.MaskingRule;
import com.whosly.gateway.masking.MaskingRuleRegistry;
import com.whosly.gateway.masking.NullingRule;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SqlServerResultSetMaskingInterceptorTest {

    private static final TdsFrameCodec CODEC = new TdsFrameCodec();

    @Test
    void rewritesNvarcharRowWithFixedRule() {
        SqlServerSession session = new SqlServerSession("mask-1");
        SqlServerDatabaseEventExtractor extractor =
                new SqlServerDatabaseEventExtractor("SQLServer", session.getConnectionId(), session);

        byte[] payload = TdsTestFrames.concat(
                TdsTestFrames.nvarcharColumnMeta("email", 100, true),
                TdsTestFrames.nvarcharRow("secret@example.com"),
                TdsTestFrames.doneFinal());
        byte[] packet = CODEC.packet(TdsPacketType.TABULAR_RESULT.getCode(), payload, 1);

        extractor.inspect(TrafficDirection.TARGET_TO_CLIENT, packet, 0, packet.length);
        assertThat(extractor.lastTargetMessageContainedRow()).isTrue();
        assertThat(extractor.currentTdsColumns()).hasSize(1);

        SqlServerResultSetMaskingInterceptor interceptor = interceptor(extractor,
                new FixedValueRule("email-fixed", 10, ColumnSelector.named("email"), "REDACTED"));
        TrafficDecision decision = interceptor.intercept(
                RawBackedMessage.of(TrafficDirection.TARGET_TO_CLIENT, packet, 0, packet.length));

        assertThat(decision.action()).isEqualTo(TrafficAction.FORWARD);
        assertThat(decision.message().mutated()).isTrue();
        byte[] rewritten = decision.message().outputBytes();
        byte[] rewrittenPayload = SqlServerDatabaseEventExtractor.concatenatePayloads(
                rewritten, 0, rewritten.length);
        byte[] redactedUtf16 = "REDACTED".getBytes(StandardCharsets.UTF_16LE);
        byte[] secretUtf16 = "secret@example.com".getBytes(StandardCharsets.UTF_16LE);
        assertThat(indexOf(rewrittenPayload, redactedUtf16)).isGreaterThanOrEqualTo(0);
        assertThat(indexOf(rewrittenPayload, secretUtf16)).isLessThan(0);
    }

    @Test
    void nullsNvarcharWhenNullingRuleMatches() {
        SqlServerSession session = new SqlServerSession("mask-2");
        SqlServerDatabaseEventExtractor extractor =
                new SqlServerDatabaseEventExtractor("SQLServer", session.getConnectionId(), session);
        byte[] payload = TdsTestFrames.concat(
                TdsTestFrames.nvarcharColumnMeta("email", 100, true),
                TdsTestFrames.nvarcharRow("secret@example.com"),
                TdsTestFrames.doneFinal());
        byte[] packet = CODEC.packet(TdsPacketType.TABULAR_RESULT.getCode(), payload, 1);
        extractor.inspect(TrafficDirection.TARGET_TO_CLIENT, packet, 0, packet.length);

        SqlServerResultSetMaskingInterceptor interceptor = interceptor(extractor,
                new NullingRule("email-null", 10, ColumnSelector.named("email")));
        TrafficDecision decision = interceptor.intercept(
                RawBackedMessage.of(TrafficDirection.TARGET_TO_CLIENT, packet, 0, packet.length));
        assertThat(decision.action()).isEqualTo(TrafficAction.FORWARD);
        assertThat(decision.message().mutated()).isTrue();
        byte[] rewrittenPayload = SqlServerDatabaseEventExtractor.concatenatePayloads(
                decision.message().outputBytes(), 0, decision.message().outputBytes().length);
        boolean sawNull = false;
        for (int i = 0; i + 2 < rewrittenPayload.length; i++) {
            if ((rewrittenPayload[i] & 0xFF) == TdsTokenType.ROW.getCode()
                    && (rewrittenPayload[i + 1] & 0xFF) == 0xFF
                    && (rewrittenPayload[i + 2] & 0xFF) == 0xFF) {
                sawNull = true;
                break;
            }
        }
        assertThat(sawNull).isTrue();
    }

    @Test
    void deniesNbcRowWhenRuleApplies() {
        SqlServerSession session = new SqlServerSession("mask-3");
        SqlServerDatabaseEventExtractor extractor =
                new SqlServerDatabaseEventExtractor("SQLServer", session.getConnectionId(), session);
        byte[] metaPacket = CODEC.packet(TdsPacketType.TABULAR_RESULT.getCode(),
                TdsTestFrames.concat(TdsTestFrames.nvarcharColumnMeta("email", 100, true),
                        TdsTestFrames.doneFinal()), 1);
        extractor.inspect(TrafficDirection.TARGET_TO_CLIENT, metaPacket, 0, metaPacket.length);

        byte[] nbc = new byte[]{(byte) TdsTokenType.NBCROW.getCode(), 0x00};
        byte[] nbcPacket = CODEC.packet(TdsPacketType.TABULAR_RESULT.getCode(),
                TdsTestFrames.concat(nbc, TdsTestFrames.doneFinal()), 2);
        extractor.inspect(TrafficDirection.TARGET_TO_CLIENT, nbcPacket, 0, nbcPacket.length);
        assertThat(extractor.lastTargetMessageContainedNbcRow()).isTrue();

        SqlServerResultSetMaskingInterceptor interceptor = interceptor(extractor,
                new FixedValueRule("email-fixed", 10, ColumnSelector.named("email"), "X"));
        TrafficDecision decision = interceptor.intercept(
                RawBackedMessage.of(TrafficDirection.TARGET_TO_CLIENT, nbcPacket, 0, nbcPacket.length));
        assertThat(decision.action()).isEqualTo(TrafficAction.DENY);
    }

    @Test
    void forwardsUnchangedWhenNoRuleMatches() {
        SqlServerSession session = new SqlServerSession("mask-4");
        SqlServerDatabaseEventExtractor extractor =
                new SqlServerDatabaseEventExtractor("SQLServer", session.getConnectionId(), session);
        byte[] payload = TdsTestFrames.concat(
                TdsTestFrames.nvarcharColumnMeta("email", 100, true),
                TdsTestFrames.nvarcharRow("keep@example.com"),
                TdsTestFrames.doneFinal());
        byte[] packet = CODEC.packet(TdsPacketType.TABULAR_RESULT.getCode(), payload, 1);
        extractor.inspect(TrafficDirection.TARGET_TO_CLIENT, packet, 0, packet.length);

        SqlServerResultSetMaskingInterceptor interceptor = interceptor(extractor,
                new FixedValueRule("other", 10, ColumnSelector.named("other"), "X"));
        TrafficDecision decision = interceptor.intercept(
                RawBackedMessage.of(TrafficDirection.TARGET_TO_CLIENT, packet, 0, packet.length));
        assertThat(decision.action()).isEqualTo(TrafficAction.FORWARD);
        assertThat(decision.message().mutated()).isFalse();
    }

    private static SqlServerResultSetMaskingInterceptor interceptor(
            SqlServerDatabaseEventExtractor extractor, MaskingRule... rules) {
        return new SqlServerResultSetMaskingInterceptor(extractor,
                new MaskingEngine(new MaskingRuleRegistry(List.of(rules))));
    }

    private static int indexOf(byte[] haystack, byte[] needle) {
        outer:
        for (int i = 0; i + needle.length <= haystack.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }
}
