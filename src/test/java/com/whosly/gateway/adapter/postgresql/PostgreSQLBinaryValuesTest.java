package com.whosly.gateway.adapter.postgresql;

import com.whosly.gateway.masking.ColumnMetadata;
import com.whosly.gateway.masking.MaskedValue;
import com.whosly.gateway.masking.MaskingException;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PostgreSQLBinaryValuesTest {

    @Test
    void passesTextFormattedValuesThrough() {
        byte[] encoded = PostgreSQLBinaryValues.encode(ColumnMetadata.text("email"), text("a@b.com"));

        assertThat(encoded).isEqualTo(bytes("a@b.com"));
    }

    @Test
    void keepsTheBytesOfTypesWhoseBinaryFormIsTheValue() {
        // A text column in binary format carries the string's bytes, not a length or a
        // wrapper, so a masked value needs no translation at all.
        assertThat(PostgreSQLBinaryValues.encode(binary("email", "varchar"), text("***")))
                .isEqualTo(bytes("***"));
        assertThat(PostgreSQLBinaryValues.encode(binary("note", "text"), text("***")))
                .isEqualTo(bytes("***"));
        assertThat(PostgreSQLBinaryValues.encode(binary("payload", "bytea"), text("***")))
                .isEqualTo(bytes("***"));
        assertThat(PostgreSQLBinaryValues.encode(binary("doc", "json"), text("{}")))
                .isEqualTo(bytes("{}"));
    }

    @Test
    void keepsByteaBytesRawInsteadOfHexEncodingThem() {
        byte[] raw = new byte[]{0x00, (byte) 0xFF, 0x10};

        // Binary bytea is the bytes themselves; the text format's \x hex form is not.
        assertThat(PostgreSQLBinaryValues.encode(binary("payload", "bytea"), MaskedValue.of(raw)))
                .isEqualTo(raw);
    }

    @Test
    void addsTheJsonbVersionByte() {
        byte[] encoded = PostgreSQLBinaryValues.encode(binary("doc", "jsonb"), text("{\"a\":1}"));

        assertThat(encoded[0]).isEqualTo((byte) 1);
        assertThat(new String(encoded, 1, encoded.length - 1, StandardCharsets.US_ASCII))
                .isEqualTo("{\"a\":1}");
    }

    @Test
    void writesNumericDateTimeAndUuidBinaryValues() {
        assertThat(PostgreSQLBinaryValues.encode(binary("price", "numeric"), text("0")))
                .containsExactly(0, 0, 0, 0, 0, 0, 0, 0);
        assertThat(PostgreSQLBinaryValues.encode(binary("price", "numeric"), text("1")))
                .containsExactly(0, 1, 0, 0, 0, 0, 0, 0, 0, 1);
        assertThat(PostgreSQLBinaryValues.encode(binary("price", "numeric"), text("0.01")))
                .containsExactly(0, 1, (byte) 0xFF, (byte) 0xFF, 0, 0, 0, 2, 0, 100);
        assertThat(PostgreSQLBinaryValues.encode(binary("d", "date"), text("2000-01-01")))
                .containsExactly(0, 0, 0, 0);
        assertThat(PostgreSQLBinaryValues.encode(binary("d", "date"), text("2000-01-02")))
                .containsExactly(0, 0, 0, 1);
        assertThat(PostgreSQLBinaryValues.encode(binary("t", "time"), text("00:00:01")))
                .isEqualTo(ByteBuffer.allocate(8).putLong(1_000_000L).array());
        assertThat(PostgreSQLBinaryValues.encode(binary("ts", "timestamp"), text("2000-01-01T00:00:01")))
                .isEqualTo(ByteBuffer.allocate(8).putLong(1_000_000L).array());
        assertThat(PostgreSQLBinaryValues.encode(binary("tsz", "timestamptz"), text("2000-01-01T00:00:01Z")))
                .isEqualTo(ByteBuffer.allocate(8).putLong(1_000_000L).array());

        UUID uuid = UUID.fromString("550e8400-e29b-41d4-a716-446655440000");
        byte[] encodedUuid = PostgreSQLBinaryValues.encode(binary("id", "uuid"), text(uuid.toString()));
        assertThat(encodedUuid).hasSize(16);
        assertThat(ByteBuffer.wrap(encodedUuid).getLong()).isEqualTo(uuid.getMostSignificantBits());
        assertThat(ByteBuffer.wrap(encodedUuid, 8, 8).getLong()).isEqualTo(uuid.getLeastSignificantBits());
    }

    @Test
    void refusesTypesWhoseBinaryEncodingIsNotReproduced() {
        for (String typeName : new String[]{"interval", "money", "timetz", "oid:987654"}) {
            assertThatThrownBy(() -> PostgreSQLBinaryValues.encode(binary("column", typeName), text("***")))
                    .as("binary %s", typeName)
                    .isInstanceOf(MaskingException.class)
                    .hasMessageContaining("cannot carry a non-null masked value");
        }
        assertThatThrownBy(() -> PostgreSQLBinaryValues.encode(binary("id", "uuid"), text("***")))
                .isInstanceOf(MaskingException.class)
                .hasMessageContaining("not a uuid");
        assertThatThrownBy(() -> PostgreSQLBinaryValues.encode(binary("price", "numeric"), text("***")))
                .isInstanceOf(MaskingException.class)
                .hasMessageContaining("not a numeric literal");
    }

    @Test
    void writesFixedWidthIntegerAndBooleanBinaryValues() {
        assertThat(PostgreSQLBinaryValues.encode(binary("id", "int4"), text("7")))
                .containsExactly(0, 0, 0, 7);
        assertThat(PostgreSQLBinaryValues.encode(binary("id", "int2"), text("258")))
                .containsExactly(0x01, 0x02);
        assertThat(PostgreSQLBinaryValues.encode(binary("id", "int8"), text("1")))
                .containsExactly(0, 0, 0, 0, 0, 0, 0, 1);
        assertThat(PostgreSQLBinaryValues.encode(binary("ok", "bool"), text("t")))
                .containsExactly(1);
        assertThat(PostgreSQLBinaryValues.encode(binary("ok", "bool"), text("false")))
                .containsExactly(0);
        assertThat(PostgreSQLBinaryValues.encode(binary("ratio", "float8"), text("1.5")))
                .isEqualTo(java.nio.ByteBuffer.allocate(8).putDouble(1.5d).array());
    }

    @Test
    void refusesNonNumericMaskForIntegerBinaryColumns() {
        assertThatThrownBy(() -> PostgreSQLBinaryValues.encode(binary("id", "int4"), text("***")))
                .isInstanceOf(MaskingException.class)
                .hasMessageContaining("not an integer");
    }

    @Test
    void refusesToEncodeANullValue() {
        // NULL is written as the protocol's own marker by the row encoder; asking for
        // bytes for it would mean one of the two layers is confused.
        assertThatThrownBy(() -> PostgreSQLBinaryValues.encode(ColumnMetadata.text("email"),
                MaskedValue.ofNull()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("NULL marker");
    }

    private static ColumnMetadata binary(String name, String typeName) {
        ColumnMetadata.Category category = switch (typeName) {
            case "bytea" -> ColumnMetadata.Category.BINARY;
            case "numeric", "int2", "int4", "int8", "float4", "float8" -> ColumnMetadata.Category.NUMERIC;
            case "date", "time", "timestamp", "timestamptz", "interval", "timetz" ->
                    ColumnMetadata.Category.TEMPORAL;
            case "uuid" -> ColumnMetadata.Category.UUID;
            case "bool" -> ColumnMetadata.Category.BOOLEAN;
            default -> ColumnMetadata.Category.TEXT;
        };
        return new ColumnMetadata(name, Optional.empty(), typeName, category,
                ColumnMetadata.ValueFormat.BINARY, true);
    }

    private static MaskedValue text(String value) {
        return MaskedValue.ofText(value, StandardCharsets.US_ASCII);
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.US_ASCII);
    }
}
