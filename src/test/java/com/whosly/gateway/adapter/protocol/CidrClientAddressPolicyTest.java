package com.whosly.gateway.adapter.protocol;

import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CidrClientAddressPolicyTest {

    @Test
    void emptyConfigurationAllowsEverything() throws Exception {
        ClientAddressPolicy policy = CidrClientAddressPolicy.of(List.of());

        assertThat(policy.isAllowed(InetAddress.getByName("203.0.113.9"))).isTrue();
    }

    @Test
    void matchesIpv4RangeAndExactHost() throws Exception {
        ClientAddressPolicy policy = CidrClientAddressPolicy.of(List.of("10.0.0.0/8", "192.168.1.5"));

        assertThat(policy.isAllowed(InetAddress.getByName("10.20.30.40"))).isTrue();
        assertThat(policy.isAllowed(InetAddress.getByName("192.168.1.5"))).isTrue();
        assertThat(policy.isAllowed(InetAddress.getByName("192.168.1.6"))).isFalse();
        assertThat(policy.isAllowed(InetAddress.getByName("11.0.0.1"))).isFalse();
    }

    @Test
    void matchesIpv6LoopbackAndRejectsOthers() throws Exception {
        ClientAddressPolicy policy = CidrClientAddressPolicy.of(List.of("::1/128"));

        assertThat(policy.isAllowed(InetAddress.getByName("::1"))).isTrue();
        assertThat(policy.isAllowed(InetAddress.getByName("2001:db8::1"))).isFalse();
    }

    @Test
    void acceptsIpv4MappedIpv6LoopbackForIpv4Rule() throws Exception {
        ClientAddressPolicy policy = CidrClientAddressPolicy.of(List.of("127.0.0.1/32"));

        assertThat(policy.isAllowed(InetAddress.getByName("::ffff:127.0.0.1"))).isTrue();
    }

    @Test
    void rejectsInvalidCidrPrefix() {
        assertThatThrownBy(() -> CidrClientAddressPolicy.of(List.of("10.0.0.0/33")))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
