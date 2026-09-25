package com.whosly.gateway.adapter.sqlserver;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class Login7ObservationTest {

    @Test
    void parsesUsernameAndDatabaseFromLogin7Payload() {
        byte[] payload = buildLogin7Payload("sa", "master", "JDBC", "client-host");
        Optional<Login7Observation> parsed = Login7Observation.tryParse(payload);
        assertThat(parsed).isPresent();
        assertThat(parsed.get().username()).contains("sa");
        assertThat(parsed.get().database()).contains("master");
        assertThat(parsed.get().appName()).contains("JDBC");
        assertThat(parsed.get().hostName()).contains("client-host");
    }

    @Test
    void returnsEmptyForTruncatedPayload() {
        assertThat(Login7Observation.tryParse(new byte[8])).isEmpty();
        assertThat(Login7Observation.tryParse(null)).isEmpty();
    }

    /**
     * Minimal Login7 payload: 36-byte fixed header + 9 OffsetLength pairs + UCS-2LE strings.
     * Password slot is filled with placeholder length but content is not asserted.
     */
    static byte[] buildLogin7Payload(String username, String database, String appName, String hostName) {
        byte[] host = encodeUtf16Le(hostName);
        byte[] user = encodeUtf16Le(username);
        byte[] pass = encodeUtf16Le("x"); // placeholder; never observed
        byte[] app = encodeUtf16Le(appName);
        byte[] server = encodeUtf16Le("");
        byte[] unused = encodeUtf16Le("");
        byte[] cltInt = encodeUtf16Le("");
        byte[] language = encodeUtf16Le("");
        byte[] db = encodeUtf16Le(database);

        int fixed = 36;
        int ol = 9 * 4; // Host..Database
        int stringsStart = fixed + ol;
        int total = stringsStart + host.length + user.length + pass.length + app.length
                + server.length + unused.length + cltInt.length + language.length + db.length;

        ByteBuffer buf = ByteBuffer.allocate(total).order(ByteOrder.LITTLE_ENDIAN);
        // length
        buf.putInt(total);
        // tdsVersion, packetSize, progVer, pid, connectionId
        buf.putInt(0x74000004); // TDS 7.4 example
        buf.putInt(4096);
        buf.putInt(0);
        buf.putInt(1);
        buf.putInt(0);
        // option flags (4) + timeZone (4) + lcid (4) = 12 → fixed ends at 36
        buf.putInt(0);
        buf.putInt(0);
        buf.putInt(0);

        int cursor = stringsStart;
        cursor = putOffsetLength(buf, cursor, host);
        cursor = putOffsetLength(buf, cursor, user);
        cursor = putOffsetLength(buf, cursor, pass);
        cursor = putOffsetLength(buf, cursor, app);
        cursor = putOffsetLength(buf, cursor, server);
        cursor = putOffsetLength(buf, cursor, unused);
        cursor = putOffsetLength(buf, cursor, cltInt);
        cursor = putOffsetLength(buf, cursor, language);
        putOffsetLength(buf, cursor, db);

        // append strings in same order
        buf.position(stringsStart);
        buf.put(host);
        buf.put(user);
        buf.put(pass);
        buf.put(app);
        buf.put(server);
        buf.put(unused);
        buf.put(cltInt);
        buf.put(language);
        buf.put(db);
        return buf.array();
    }

    private static int putOffsetLength(ByteBuffer buf, int ib, byte[] data) {
        int cch = data.length / 2;
        buf.putShort((short) ib);
        buf.putShort((short) cch);
        return ib + data.length;
    }

    private static byte[] encodeUtf16Le(String value) {
        if (value == null || value.isEmpty()) {
            return new byte[0];
        }
        return value.getBytes(StandardCharsets.UTF_16LE);
    }
}
