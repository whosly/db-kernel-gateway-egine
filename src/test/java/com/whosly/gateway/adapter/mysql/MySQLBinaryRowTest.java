package com.whosly.gateway.adapter.mysql;

import com.whosly.gateway.masking.ColumnMetadata;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MySQLBinaryRowTest {

    @Test
    void parsesFixedWidthAndLengthEncodedValues() {
        byte[] row = row(List.of(column("id", "int"), column("email", "varchar")),
                littleEndian(7, 4), lengthEncoded("alice@example.com"));

        Optional<List<byte[]>> parsed = MySQLBinaryRow.parse(row, 0, row.length,
                List.of(column("id", "int"), column("email", "varchar")));

        assertThat(parsed).isPresent();
        assertThat(parsed.get().get(0)).isEqualTo(littleEndian(7, 4));
        assertThat(parsed.get().get(1)).isEqualTo(lengthEncoded("alice@example.com"));
    }

    @Test
    void readsTheNullBitmapAndSkipsTheMissingValue() {
        List<ColumnMetadata> columns = List.of(column("id", "int"), column("email", "varchar"));
        byte[] row = row(columns, null, lengthEncoded("alice@example.com"));

        List<byte[]> values = MySQLBinaryRow.parse(row, 0, row.length, columns).orElseThrow();

        // A NULL column contributes a bit, not bytes — and must not consume the next
        // column's value.
        assertThat(values.get(0)).isNull();
        assertThat(values.get(1)).isEqualTo(lengthEncoded("alice@example.com"));
    }

    @Test
    void addressesTheBitmapWithTheProtocolsTwoBitOffset() {
        List<ColumnMetadata> columns = new ArrayList<>();
        for (int index = 0; index < 8; index++) {
            columns.add(column("c" + index, "tinyint"));
        }
        byte[][] values = new byte[8][];
        for (int index = 0; index < values.length; index++) {
            values[index] = littleEndian(index, 1);
        }
        values[7] = null;

        byte[] row = row(columns, values);

        // The bitmap spans two bytes here, and column 7 lives in the second one: getting
        // the +2 offset wrong would read a different column's null-ness.
        assertThat(MySQLBinaryRow.bitmapLength(8)).isEqualTo(2);
        List<byte[]> parsed = MySQLBinaryRow.parse(row, 0, row.length, columns).orElseThrow();
        assertThat(parsed.get(7)).isNull();
        for (int index = 0; index < 7; index++) {
            assertThat(parsed.get(index)).isEqualTo(littleEndian(index, 1));
        }
    }

    @Test
    void reportsATypeWhoseLayoutIsUnknown() {
        List<ColumnMetadata> columns = List.of(column("flags", "bit"));

        // Guessing where a BIT value ends would shift every following value, so the row
        // is reported as unparseable instead.
        assertThat(MySQLBinaryRow.parse(row(columns, littleEndian(1, 1)), 0, 5, columns)).isEmpty();
    }

    @Test
    void reportsBytesLeftOverAfterTheLastColumn() {
        List<ColumnMetadata> columns = List.of(column("id", "int"));
        byte[] complete = row(columns, littleEndian(7, 4));
        byte[] withTrailer = Arrays.copyOf(complete, complete.length + 1);

        // Extra bytes mean a type was read with the wrong width, so the values cannot be
        // trusted even though every column parsed.
        assertThat(MySQLBinaryRow.parse(withTrailer, 0, withTrailer.length, columns)).isEmpty();
    }

    @Test
    void reportsATruncatedPayload() {
        List<ColumnMetadata> columns = List.of(column("id", "int"));
        byte[] complete = row(columns, littleEndian(7, 4));

        assertThat(MySQLBinaryRow.parse(complete, 0, complete.length - 1, columns)).isEmpty();
    }

    @Test
    void reportsAPayloadThatIsNotARow() {
        List<ColumnMetadata> columns = List.of(column("id", "int"));
        byte[] notARow = row(columns, littleEndian(7, 4));
        notARow[0] = (byte) 0xFF;

        assertThat(MySQLBinaryRow.parse(notARow, 0, notARow.length, columns)).isEmpty();
    }

    @Test
    void encodesWhatItParses() {
        List<ColumnMetadata> columns = List.of(
                column("id", "int"), column("email", "varchar"), column("age", "tinyint"));
        List<byte[]> values = List.of(littleEndian(7, 4), lengthEncoded("alice@example.com"),
                littleEndian(42, 1));

        List<byte[]> reparsed = MySQLBinaryRow.parse(
                MySQLBinaryRow.encode(columns, values), 0,
                MySQLBinaryRow.encode(columns, values).length, columns).orElseThrow();

        assertThat(reparsed).containsExactlyElementsOf(values);
    }

    @Test
    void roundTripsANullColumn() {
        List<ColumnMetadata> columns = List.of(column("id", "int"), column("email", "varchar"));
        List<byte[]> values = Arrays.asList(littleEndian(7, 4), null);

        byte[] encoded = MySQLBinaryRow.encode(columns, values);

        assertThat(MySQLBinaryRow.parse(encoded, 0, encoded.length, columns).orElseThrow())
                .satisfies(parsed -> {
                    assertThat(parsed.get(0)).isEqualTo(littleEndian(7, 4));
                    assertThat(parsed.get(1)).isNull();
                });
    }

    @Test
    void requiresOneValuePerColumn() {
        List<ColumnMetadata> columns = List.of(column("id", "int"), column("email", "varchar"));

        assertThatThrownBy(() -> MySQLBinaryRow.encode(columns, List.of(littleEndian(7, 4))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("One value per column");
    }

    private static byte[] row(List<ColumnMetadata> columns, byte[]... values) {
        return MySQLBinaryRow.encode(columns, Arrays.asList(values));
    }

    private static ColumnMetadata column(String name, String typeName) {
        return new ColumnMetadata(name, Optional.empty(), typeName,
                ColumnMetadata.Category.NUMERIC, ColumnMetadata.ValueFormat.BINARY, true);
    }

    private static byte[] littleEndian(long value, int width) {
        byte[] bytes = new byte[width];
        for (int index = 0; index < width; index++) {
            bytes[index] = (byte) ((value >> (8 * index)) & 0xFF);
        }
        return bytes;
    }

    private static byte[] lengthEncoded(String value) {
        byte[] bytes = value.getBytes(StandardCharsets.US_ASCII);
        ByteArrayOutputStream encoded = new ByteArrayOutputStream();
        encoded.write(bytes.length);
        encoded.writeBytes(bytes);
        return encoded.toByteArray();
    }
}
