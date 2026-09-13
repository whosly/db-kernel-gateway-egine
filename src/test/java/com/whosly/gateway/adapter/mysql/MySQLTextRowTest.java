package com.whosly.gateway.adapter.mysql;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class MySQLTextRowTest {

    @Test
    void parsesValuesInColumnOrder() {
        byte[] row = row(bytes("7"), bytes("alice@example.com"));

        Optional<List<byte[]>> parsed = MySQLTextRow.parse(row, 0, row.length);

        assertThat(parsed).isPresent();
        assertThat(parsed.get()).hasSize(2);
        assertThat(new String(parsed.get().get(0), StandardCharsets.US_ASCII)).isEqualTo("7");
        assertThat(new String(parsed.get().get(1), StandardCharsets.US_ASCII)).isEqualTo("alice@example.com");
    }

    @Test
    void parsesTheNullMarker() {
        byte[] row = row(bytes("7"), null);

        Optional<List<byte[]>> parsed = MySQLTextRow.parse(row, 0, row.length);

        assertThat(parsed).isPresent();
        assertThat(parsed.get().get(0)).isNotNull();
        assertThat(parsed.get().get(1)).isNull();
    }

    @Test
    void parsesValuesThatNeedMultiByteLengths() {
        byte[] medium = filled(300, 'm');
        byte[] large = filled(70_000, 'l');
        byte[] row = row(medium, large);

        Optional<List<byte[]>> parsed = MySQLTextRow.parse(row, 0, row.length);

        assertThat(parsed).isPresent();
        assertThat(parsed.get().get(0)).isEqualTo(medium);
        assertThat(parsed.get().get(1)).isEqualTo(large);
    }

    @Test
    void reportsARowWhoseLengthRunsPastTheEnd() {
        byte[] row = row(bytes("value"));
        // Claim a longer value than the payload holds.
        row[0] = (byte) 0x20;

        assertThat(MySQLTextRow.parse(row, 0, row.length)).isEmpty();
    }

    @Test
    void reportsAnInvalidLengthPrefix() {
        byte[] row = new byte[]{(byte) 0xFF, 0x01, 0x02};

        assertThat(MySQLTextRow.parse(row, 0, row.length)).isEmpty();
    }

    @Test
    void reportsAPayloadThatIsNotFullyAvailable() {
        byte[] row = row(bytes("value"));

        assertThat(MySQLTextRow.parse(row, 0, row.length + 1)).isEmpty();
    }

    @Test
    void roundTripsValuesAndNulls() {
        List<byte[]> values = java.util.Arrays.asList(bytes("7"), null, bytes("alice"), new byte[0]);

        byte[] encoded = MySQLTextRow.encode(values);

        assertThat(MySQLTextRow.parse(encoded, 0, encoded.length).orElseThrow()).satisfies(decoded -> {
            assertThat(decoded).hasSize(4);
            assertThat(decoded.get(0)).isEqualTo(bytes("7"));
            assertThat(decoded.get(1)).isNull();
            assertThat(decoded.get(2)).isEqualTo(bytes("alice"));
            assertThat(decoded.get(3)).isEmpty();
        });
    }

    @Test
    void encodesEachLengthWithTheShortestPrefix() {
        byte[] encoded = MySQLTextRow.encode(List.of(filled(300, 'm'), filled(70_000, 'l')));

        // 300 needs the two-byte prefix, 70_000 the three-byte one.
        assertThat(encoded[0] & 0xFF).isEqualTo(0xFC);
        int secondValueOffset = 3 + 300;
        assertThat(encoded[secondValueOffset] & 0xFF).isEqualTo(0xFD);
    }

    @Test
    void treatsAnEmptyPayloadAsNoValues() {
        assertThat(MySQLTextRow.parse(new byte[0], 0, 0)).contains(List.of());
    }

    private static byte[] row(byte[]... values) {
        return MySQLTextRow.encode(java.util.Arrays.asList(values));
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.US_ASCII);
    }

    private static byte[] filled(int length, char value) {
        byte[] bytes = new byte[length];
        java.util.Arrays.fill(bytes, (byte) value);
        return bytes;
    }
}
