package com.whosly.gateway.adapter.sqlserver;

import java.nio.charset.StandardCharsets;
import java.util.Optional;

/**
 * Best-effort parse of a TDS RPC request payload (name or proc id only).
 *
 * <p>Parameters are not decoded — observation label only. Fail-open: truncated
 * or unfamiliar layouts yield empty.</p>
 */
public final class RpcObservation {

    private final String name;
    private final Integer procId;

    private RpcObservation(String name, Integer procId) {
        this.name = name;
        this.procId = procId;
    }

    public Optional<String> name() {
        return Optional.ofNullable(name);
    }

    public Optional<Integer> procId() {
        return Optional.ofNullable(procId);
    }

    public String displayName() {
        if (name != null && !name.isBlank()) {
            return name;
        }
        if (procId != null) {
            return "procId:" + procId;
        }
        return "(rpc)";
    }

    /**
     * @param payload RPC packet payload (headers already concatenated)
     */
    public static Optional<RpcObservation> tryParse(byte[] payload) {
        if (payload == null || payload.length < 2) {
            return Optional.empty();
        }
        int cursor = 0;
        // Optional ALL_HEADERS (TDS 7.2+): TotalLength DWORD.
        if (payload.length >= 4) {
            int totalHeaderLen = u32le(payload, 0);
            if (totalHeaderLen >= 4 && totalHeaderLen < payload.length) {
                cursor = totalHeaderLen;
            }
        }
        if (cursor + 2 > payload.length) {
            return Optional.empty();
        }
        int nameLenChars = u16le(payload, cursor);
        cursor += 2;
        if (nameLenChars == 0xFFFF) {
            // ProcId present
            if (cursor + 2 > payload.length) {
                return Optional.empty();
            }
            int procId = u16le(payload, cursor);
            return Optional.of(new RpcObservation(null, procId));
        }
        int nameBytes = nameLenChars * 2;
        if (cursor + nameBytes > payload.length) {
            return Optional.empty();
        }
        String name = nameLenChars == 0
                ? ""
                : new String(payload, cursor, nameBytes, StandardCharsets.UTF_16LE);
        return Optional.of(new RpcObservation(name, null));
    }

    private static int u16le(byte[] bytes, int offset) {
        return (bytes[offset] & 0xFF) | ((bytes[offset + 1] & 0xFF) << 8);
    }

    private static int u32le(byte[] bytes, int offset) {
        return (bytes[offset] & 0xFF)
                | ((bytes[offset + 1] & 0xFF) << 8)
                | ((bytes[offset + 2] & 0xFF) << 16)
                | ((bytes[offset + 3] & 0xFF) << 24);
    }
}
