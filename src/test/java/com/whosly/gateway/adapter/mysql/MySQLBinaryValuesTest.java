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
    void refusesTypesWhoseBinaryLayoutIsNotReproduced() {
        assertThatThrownBy(() -> MySQLBinaryValues.encode(column("price", "decimal"), text("0")))
                .isInstanceOf(MaskingException.class)
                .hasMessageContaining("Packed decimal");
        for (String typeName : new String[]{"datetime", "timestamp", "date", "time", "bit", "geometry"}) {
            assertThatThrownBy(() -> MySQLBinaryValues.encode(column("column", typeName), text("0")))
                    .as("binary %s", typeName)
                    .isInstanceOf(MaskingException.class)
                    .hasMessageContaining("cannot carry a non-null masked value");
        }
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
        assertThat(length("bit", new byte[]{1})).isEmpty();
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
