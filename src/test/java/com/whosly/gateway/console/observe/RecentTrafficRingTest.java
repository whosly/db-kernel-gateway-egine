package com.whosly.gateway.console.observe;

import com.whosly.gateway.adapter.protocol.DatabaseTrafficEvent;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RecentTrafficRingTest {

    @Test
    void capacityBoundsAndNewestFirst() {
        RecentTrafficRing ring = new RecentTrafficRing(3, 10);
        ring.record("a", event("one-aaaaaaa"));
        ring.record("a", event("two-bbbbbbb"));
        ring.record("a", event("three-cccc"));
        ring.record("a", event("four-ddddddddddd"));
        assertThat(ring.size()).isEqualTo(3);
        List<RecentTrafficRing.RecentEntry> recent = ring.recent("a", 10);
        assertThat(recent).hasSize(3);
        assertThat(recent.get(0).statement()).startsWith("four-");
        assertThat(recent.get(0).statement()).contains("…");
    }

    @Test
    void rejectsNonPositiveCapacity() {
        assertThatThrownBy(() -> new RecentTrafficRing(0)).isInstanceOf(IllegalArgumentException.class);
    }

    private static DatabaseTrafficEvent event(String stmt) {
        return DatabaseTrafficEvent.builder("MySQL", "s1", "COM_QUERY", stmt).build();
    }
}
