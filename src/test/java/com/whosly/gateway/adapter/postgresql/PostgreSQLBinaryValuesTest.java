package com.whosly.gateway.adapter.postgresql;

import com.whosly.gateway.masking.ColumnMetadata;
import com.whosly.gateway.masking.MaskedValue;
import com.whosly.gateway.masking.MaskingException;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Optional;

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
    void refusesTypesWhoseBinaryEncodingIsNotReproduced() {
        // A client reads these bytes with the type it asked for: filling them with a text
        // mask would be a corrupted result set, not a masked one.
        for (String typeName : new String[]{"int4", "int8", "numeric", "float8", "bool",
                "timestamp", "timestamptz", "date", "uuid", "oid:987654"}) {
            assertThatThrownBy(() -> PostgreSQLBinaryValues.encode(binary("column", typeName), text("***")))
                    .as("binary %s", typeName)
                    .isInstanceOf(MaskingException.class)
                    .hasMessageContaining("cannot carry a non-null masked value");
        }
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
        ColumnMetadata.Category category = "bytea".equals(typeName)
                ? ColumnMetadata.Category.BINARY
                : ColumnMetadata.Category.TEXT;
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
