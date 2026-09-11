package com.whosly.gateway.adapter.protocol;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;

/**
 * {@link ClientAddressPolicy} backed by a CIDR allowlist.
 *
 * <p>Accepts IPv4 and IPv6 ranges such as {@code 10.0.0.0/8}, {@code 192.168.1.5}
 * (exact host) or {@code ::1/128}. IPv4-mapped IPv6 client addresses are
 * normalized to IPv4 so an IPv4 rule still matches on dual-stack listeners.</p>
 *
 * @author yueny09@163.com codealy
 * @since 2026-07-02
 */
public final class CidrClientAddressPolicy implements ClientAddressPolicy {

    private final List<CidrBlock> blocks;

    private CidrClientAddressPolicy(List<CidrBlock> blocks) {
        this.blocks = blocks;
    }

    /**
     * Builds a policy from configured CIDR entries.
     *
     * @return {@link ClientAddressPolicy#allowAll()} when no usable entry exists,
     *         so an empty configuration keeps the previous behaviour
     */
    public static ClientAddressPolicy of(List<String> cidrs) {
        if (cidrs == null || cidrs.isEmpty()) {
            return ClientAddressPolicy.allowAll();
        }

        List<CidrBlock> parsed = new ArrayList<>();
        for (String cidr : cidrs) {
            if (cidr == null || cidr.isBlank()) {
                continue;
            }
            parsed.add(CidrBlock.parse(cidr.trim()));
        }
        if (parsed.isEmpty()) {
            return ClientAddressPolicy.allowAll();
        }
        return new CidrClientAddressPolicy(List.copyOf(parsed));
    }

    @Override
    public boolean isAllowed(InetAddress address) {
        if (address == null) {
            return false;
        }
        for (CidrBlock block : blocks) {
            if (block.contains(address)) {
                return true;
            }
        }
        return false;
    }

    /**
     * One CIDR range.
     */
    static final class CidrBlock {

        private final byte[] network;
        private final int prefixLength;

        private CidrBlock(byte[] network, int prefixLength) {
            this.network = network;
            this.prefixLength = prefixLength;
        }

        static CidrBlock parse(String value) {
            int slash = value.indexOf('/');
            String addressPart = slash < 0 ? value : value.substring(0, slash);

            InetAddress address;
            try {
                address = InetAddress.getByName(addressPart);
            } catch (UnknownHostException e) {
                throw new IllegalArgumentException("Invalid CIDR address: " + value, e);
            }

            byte[] bytes = address.getAddress();
            int maxPrefix = bytes.length * 8;
            int prefix = slash < 0 ? maxPrefix : parsePrefix(value, slash, maxPrefix);
            return new CidrBlock(bytes, prefix);
        }

        private static int parsePrefix(String value, int slash, int maxPrefix) {
            int prefix;
            try {
                prefix = Integer.parseInt(value.substring(slash + 1).trim());
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("Invalid CIDR prefix length: " + value, e);
            }
            if (prefix < 0 || prefix > maxPrefix) {
                throw new IllegalArgumentException(
                        "CIDR prefix length out of range for " + value + " (0.." + maxPrefix + ")");
            }
            return prefix;
        }

        boolean contains(InetAddress address) {
            byte[] candidate = normalize(address.getAddress());
            if (candidate.length != network.length) {
                return false;
            }

            int fullBytes = prefixLength / 8;
            for (int index = 0; index < fullBytes; index++) {
                if (candidate[index] != network[index]) {
                    return false;
                }
            }

            int remainingBits = prefixLength % 8;
            if (remainingBits == 0) {
                return true;
            }
            int mask = 0xFF << (8 - remainingBits);
            return (candidate[fullBytes] & mask) == (network[fullBytes] & mask);
        }

        private static byte[] normalize(byte[] raw) {
            if (raw.length == 16 && isIpv4Mapped(raw)) {
                byte[] ipv4 = new byte[4];
                System.arraycopy(raw, 12, ipv4, 0, 4);
                return ipv4;
            }
            return raw;
        }

        private static boolean isIpv4Mapped(byte[] raw) {
            for (int index = 0; index < 10; index++) {
                if (raw[index] != 0) {
                    return false;
                }
            }
            return (raw[10] & 0xFF) == 0xFF && (raw[11] & 0xFF) == 0xFF;
        }
    }
}
