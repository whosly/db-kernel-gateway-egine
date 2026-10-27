package com.whosly.gateway.adapter.mysql;

import com.whosly.gateway.masking.ColumnMetadata;
import com.whosly.gateway.masking.MaskedValue;
import com.whosly.gateway.masking.MaskingException;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MySQLBinaryValuesTest {

    @Test
    void writesTheStringFamilyWithALengthPrefix() {
        assertThat(MySQLBinaryValues.encode(column("email", "varchar"), text("***")))
                .containsExactly(0x03, '*', '*', '*');
        assertThat(MySQLBinaryValues.encode(column("payload", "blob"), text("ab")))
                .containsExactly(0x02, 'a', 'b');
        assertThat(MySQLBinaryValues.encode(column("doc", "json"), text("{}")))
                .containsExactly(0x02, '{', '}');
    }

    @Test
    void usesTheTwoByteLengthPrefixForLongValues() {
        byte[] mask = new byte[300];
        java.util.Arrays.fill(mask, (byte) 'x');

        byte[] encoded = MySQLBinaryValues.encode(column("payload", "blob"), MaskedValue.of(mask));

        assertThat(encoded[0] & 0xFF).isEqualTo(0xFC);
        // 300 is 0x012C, written little-endian behind the 0xFC prefix.
        assertThat(encoded[1] & 0xFF).isEqualTo(0x2C);
        assertThat(encoded[2]).isEqualTo((byte) 0x01);
        assertThat(encoded).hasSize(3 + 300);
    }

    @Test
    void writesFixedWidthIntegersLittleEndian() {
        assertThat(MySQLBinaryValues.encode(column("id", "int"), text("7")))
                .containsExactly(7, 0, 0, 0);
        assertThat(MySQLBinaryValues.encode(column("small", "smallint"), text("258")))
                .containsExactly(0x02, 0x01);
        assertThat(MySQLBinaryValues.encode(column("huge", "bigint"), text("1")))
                .containsExactly(1, 0, 0, 0, 0, 0, 0, 0);
        assertThat(MySQLBinaryValues.encode(column("tiny", "tinyint"), text("-1")))
                .containsExactly(0xFF);
    }

    @Test
    void writesFloatingPointValues() {
        assertThat(MySQLBinaryValues.encode(column("ratio", "float"), text("1.5")))
                .isEqualTo(littleEndian(Float.floatToIntBits(1.5f), 4));
        assertThat(MySQLBinaryValues.encode(column("ratio", "double"), text("1.5")))
                .isEqualTo(littleEndian(Double.doubleToLongBits(1.5d), 8));
    }

    @Test
    void refusesAValueThatDoesNotFitTheColumnWidth() {
        // Writing 300 as a tinyint would wrap to 44 and silently change the value.
        assertThatThrownBy(() -> MySQLBinaryValues.encode(column("tiny", "tinyint"), text("300")))
                .isInstanceOf(MaskingException.class)
                .hasMessageContaining("does not fit a signed tinyint");
    }

    @Test
    void refusesANonNumericMaskForANumericColumn() {
        assertThatThrownBy(() -> MySQLBinaryValues.encode(column("id", "int"), text("***")))
                .isInstanceOf(MaskingException.class)
                .hasMessageContaining("not an integer");
        assertThatThrownBy(() -> MySQLBinaryValues.encode(column("ratio", "double"), text("***")))
                .isInstanceOf(MaskingException.class)
                .hasMessageContaining("not a number");
    }

    @Test
    void writesDecimalAsLengthEncodedAscii() {
        // NEWDECIMAL on the wire is ASCII text, not packed BCD.
        assertThat(MySQLBinaryValues.encode(column("price", "decimal"), text("0")))
                .containsExactly(0x01, '0');
        assertThat(MySQLBinaryValues.encode(column("price", "decimal"), text("12.50")))
                .containsExactly(0x05, '1', '2', '.', '5', '0');
        assertThatThrownBy(() -> MySQLBinaryValues.encode(column("price", "decimal"), text("***")))
                .isInstanceOf(MaskingException.class)
                .hasMessageContaining("not a decimal literal");
    }

    @Test
    void writesDateTimeAndTimeBinaryValues() {
        assertThat(MySQLBinaryValues.encode(column("d", "date"), text("2010-10-17")))
                .containsExactly(4, 0xDA, 0x07, 0x0A, 0x11);
        assertThat(MySQLBinaryValues.encode(column("dt", "datetime"), text("2010-10-17 19:27:30")))
                .containsExactly(7, 0xDA, 0x07, 0x0A, 0x11, 0x13, 0x1B, 0x1E);
        assertThat(MySQLBinaryValues.encode(column("ts", "timestamp"), text("2010-10-17T19:27:30.000001")))
                .containsExactly(11, 0xDA, 0x07, 0x0A, 0x11, 0x13, 0x1B, 0x1E, 0x01, 0x00, 0x00, 0x00);
        assertThat(MySQLBinaryValues.encode(column("t", "time"), text("19:27:30")))
                .containsExactly(8, 0, 0, 0, 0, 0, 0x13, 0x1B, 0x1E);
        assertThat(MySQLBinaryValues.encode(column("t", "time"), text("-120 19:27:30.000001")))
                .containsExactly(12, 1, 0x78, 0, 0, 0, 0x13, 0x1B, 0x1E, 1, 0, 0, 0);
    }

    @Test
    void refusesGeometryAndMalformedTemporalMasks() {
        assertThatThrownBy(() -> MySQLBinaryValues.encode(column("g", "geometry"), text("0")))
                .isInstanceOf(MaskingException.class)
                .hasMessageContaining("cannot carry a non-null masked value");
        assertThatThrownBy(() -> MySQLBinaryValues.encode(column("d", "date"), text("not-a-date")))
                .isInstanceOf(MaskingException.class)
                .hasMessageContaining("not a date");
    }

    @Test
    void writesBitValuesAsLengthEncodedBytes() {
        assertThat(MySQLBinaryValues.encode(column("flags", "bit"), MaskedValue.of(new byte[]{0x01})))
                .containsExactly(0x01, 0x01);
        assertThat(MySQLBinaryValues.valueLength("bit", new byte[]{0x01, 0x01}, 0, 2)).contains(2);
    }

    @Test
    void refusesToEncodeANullValue() {
        // NULL is written by setting the row's null bitmap bit; asking for bytes for it
        // would mean one of the two layers is confused.
        assertThatThrownBy(() -> MySQLBinaryValues.encode(column("email", "varchar"), MaskedValue.ofNull()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("null bitmap");
    }

    @Test
    void reportsTheLengthOfEverySupportedType() {
        assertThat(length("int", new byte[]{7, 0, 0, 0})).contains(4);
        assertThat(length("tinyint", new byte[]{7})).contains(1);
        assertThat(length("varchar", new byte[]{0x03, 'a', 'b', 'c'})).contains(4);
        // A 0xFC prefix is three bytes and declares 0x012C = 300 bytes of value.
        byte[] longValue = new byte[3 + 300];
        longValue[0] = (byte) 0xFC;
        longValue[1] = 0x2C;
        longValue[2] = 0x01;
        assertThat(length("varchar", longValue)).contains(303);
        assertThat(length("datetime", new byte[]{7, 1, 2, 3, 4, 5, 6, 7})).contains(8);
        // Unknown layouts report nothing, which is what makes the row unparseable.
        assertThat(length("bit", new byte[]{0x01, 0x7F})).contains(2);
        assertThat(length("geometry", new byte[]{1})).isEmpty();
    }

    private static Optional<Integer> length(String typeName, byte[] payload) {
        return MySQLBinaryValues.valueLength(typeName, payload, 0, payload.length);
    }

    private static ColumnMetadata column(String name, String typeName) {
        return new ColumnMetadata(name, Optional.empty(), typeName,
                ColumnMetadata.Category.NUMERIC, ColumnMetadata.ValueFormat.BINARY, true);
    }

    private static MaskedValue text(String value) {
        return MaskedValue.ofText(value, StandardCharsets.US_ASCII);
    }

    private static byte[] littleEndian(long value, int width) {
        byte[] bytes = new byte[width];
        for (int index = 0; index < width; index++) {
            bytes[index] = (byte) ((value >> (8 * index)) & 0xFF);
        }
        return bytes;
    }
}
