package com.whosly.gateway.adapter.postgresql;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class PostgreSQLDataRowTest {

    @Test
    void parsesValuesInFieldOrder() {
        byte[] row = PostgreSQLDataRow.encode(List.of(bytes("7"), bytes("alice")));

        Optional<List<byte[]>> parsed = PostgreSQLDataRow.parse(row, 0, row.length);

        assertThat(parsed).isPresent();
        assertThat(parsed.get()).hasSize(2);
        assertThat(new String(parsed.get().get(0), StandardCharsets.US_ASCII)).isEqualTo("7");
        assertThat(new String(parsed.get().get(1), StandardCharsets.US_ASCII)).isEqualTo("alice");
    }

    @Test
    void parsesTheNullLengthPrefix() {
        byte[] row = PostgreSQLDataRow.encode(Arrays.asList(null, bytes("value")));

        Optional<List<byte[]>> parsed = PostgreSQLDataRow.parse(row, 0, row.length);

        assertThat(parsed).isPresent();
        assertThat(parsed.get().get(0)).isNull();
        assertThat(parsed.get().get(1)).isEqualTo(bytes("value"));
    }

    @Test
    void parsesAPayloadThatStartsAtAnOffset() {
        byte[] row = PostgreSQLDataRow.encode(List.of(bytes("value")));
        byte[] buffer = new byte[row.length + 5];
        System.arraycopy(row, 0, buffer, 5, row.length);

        assertThat(PostgreSQLDataRow.parse(buffer, 5, row.length).orElseThrow())
                .singleElement()
                .satisfies(value -> assertThat(value).isEqualTo(bytes("value")));
    }

    @Test
    void reportsAValueWhoseLengthRunsPastTheEnd() {
        byte[] row = PostgreSQLDataRow.encode(List.of(bytes("value")));
        // The length prefix sits right after the field count: claim one byte more than
        // the payload actually holds.
        assertThat(row[5]).isEqualTo((byte) 5);
        row[5] = 6;

        assertThat(PostgreSQLDataRow.parse(row, 0, row.length)).isEmpty();
    }

    @Test
    void reportsATruncatedFieldHeader() {
        byte[] row = PostgreSQLDataRow.encode(List.of(bytes("value")));

        assertThat(PostgreSQLDataRow.parse(row, 0, 4)).isEmpty();
    }

    @Test
    void reportsAPayloadShorterThanOneRow() {
        assertThat(PostgreSQLDataRow.parse(new byte[1], 0, 1)).isEmpty();
    }

    @Test
    void roundTripsValuesAndNulls() {
        List<byte[]> values = Arrays.asList(bytes("7"), null, new byte[0], bytes("alice"));

        byte[] encoded = PostgreSQLDataRow.encode(values);

        assertThat(PostgreSQLDataRow.parse(encoded, 0, encoded.length).orElseThrow()).satisfies(decoded -> {
            assertThat(decoded).hasSize(4);
            assertThat(decoded.get(0)).isEqualTo(bytes("7"));
            assertThat(decoded.get(1)).isNull();
            assertThat(decoded.get(2)).isEmpty();
            assertThat(decoded.get(3)).isEqualTo(bytes("alice"));
        });
    }

    @Test
    void writesTheFieldCountAsTwoBytes() {
        byte[] encoded = PostgreSQLDataRow.encode(java.util.Collections.nCopies(300, null));

        assertThat(encoded[0] & 0xFF).isEqualTo(0x01);
        assertThat(encoded[1] & 0xFF).isEqualTo(0x2C);
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.US_ASCII);
    }
}
