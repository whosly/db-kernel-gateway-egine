package com.whosly.gateway.adapter.mysql;

import com.whosly.gateway.masking.ColumnMetadata;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class MySQLColumnMetadataTest {

    private static final int BINARY_COLLATION = 63;
    private static final int UTF8_COLLATION = 255;

    @Test
    void parsesNameTableTypeAndCategory() {
        byte[] packet = columnDefinition("def", "shop", "accounts", "accounts", "email",
                UTF8_COLLATION, 0x0F);

        Optional<ColumnMetadata> parsed = MySQLColumnMetadata.parse(
                packet, 0, packet.length, ColumnMetadata.ValueFormat.TEXT);

        assertThat(parsed).isPresent();
        assertThat(parsed.get().name()).isEqualTo("email");
        assertThat(parsed.get().tableName()).contains("accounts");
        assertThat(parsed.get().typeName()).isEqualTo("varchar");
        assertThat(parsed.get().category()).isEqualTo(ColumnMetadata.Category.TEXT);
        assertThat(parsed.get().format()).isEqualTo(ColumnMetadata.ValueFormat.TEXT);
    }

    @Test
    void keepsNumericColumnsOutOfTheReachOfTextRules() {
        byte[] packet = columnDefinition("def", "shop", "accounts", "accounts", "id", UTF8_COLLATION, 0x08);

        ColumnMetadata column = MySQLColumnMetadata.parse(
                packet, 0, packet.length, ColumnMetadata.ValueFormat.TEXT).orElseThrow();

        assertThat(column.typeName()).isEqualTo("bigint");
        assertThat(column.category()).isEqualTo(ColumnMetadata.Category.NUMERIC);
    }

    @Test
    void decidesBinaryFromTheCollationNotTheTypeName() {
        // VARBINARY and VARCHAR share a type code; only the collation tells them apart.
        byte[] binary = columnDefinition("def", "shop", "secrets", "secrets", "token",
                BINARY_COLLATION, 0xFD);
        byte[] text = columnDefinition("def", "shop", "secrets", "secrets", "token",
                UTF8_COLLATION, 0xFD);

        assertThat(MySQLColumnMetadata.parse(binary, 0, binary.length, ColumnMetadata.ValueFormat.TEXT)
                .orElseThrow().category()).isEqualTo(ColumnMetadata.Category.BINARY);
        assertThat(MySQLColumnMetadata.parse(text, 0, text.length, ColumnMetadata.ValueFormat.TEXT)
                .orElseThrow().category()).isEqualTo(ColumnMetadata.Category.TEXT);
    }

    @Test
    void reportsBlobFamiliesAsBinaryOnlyWhenTheyAreBinaryCollated() {
        byte[] binaryBlob = columnDefinition("def", "shop", "t", "t", "payload", BINARY_COLLATION, 0xFC);

        assertThat(MySQLColumnMetadata.parse(binaryBlob, 0, binaryBlob.length,
                ColumnMetadata.ValueFormat.TEXT).orElseThrow().category())
                .isEqualTo(ColumnMetadata.Category.BINARY);
    }

    @Test
    void keepsTemporalColumnsTemporal() {
        byte[] packet = columnDefinition("def", "shop", "t", "t", "created_at", UTF8_COLLATION, 0x0C);

        assertThat(MySQLColumnMetadata.parse(packet, 0, packet.length, ColumnMetadata.ValueFormat.TEXT)
                .orElseThrow().category()).isEqualTo(ColumnMetadata.Category.TEMPORAL);
    }

    @Test
    void carriesTheWireFormatItWasGiven() {
        byte[] packet = columnDefinition("def", "shop", "t", "t", "email", UTF8_COLLATION, 0x0F);

        assertThat(MySQLColumnMetadata.parse(packet, 0, packet.length, ColumnMetadata.ValueFormat.BINARY)
                .orElseThrow().format()).isEqualTo(ColumnMetadata.ValueFormat.BINARY);
    }

    @Test
    void reportsMalformedPacketsInsteadOfGuessing() {
        byte[] truncated = columnDefinition("def", "shop", "accounts", "accounts", "email",
                UTF8_COLLATION, 0x0F);

        assertThat(MySQLColumnMetadata.parse(truncated, 0, 6, ColumnMetadata.ValueFormat.TEXT)).isEmpty();
        assertThat(MySQLColumnMetadata.parse(new byte[0], 0, 0, ColumnMetadata.ValueFormat.TEXT)).isEmpty();
        assertThat(MySQLColumnMetadata.parse(truncated, 0, truncated.length + 5,
                ColumnMetadata.ValueFormat.TEXT)).isEmpty();
    }

    @Test
    void omitsTheTableWhenTheDatabaseDidNotNameOne() {
        byte[] packet = columnDefinition("def", "", "", "", "1", UTF8_COLLATION, 0x08);

        assertThat(MySQLColumnMetadata.parse(packet, 0, packet.length, ColumnMetadata.ValueFormat.TEXT)
                .orElseThrow().tableName()).isEmpty();
    }

    /** Builds a protocol 4.1 ColumnDefinition payload. */
    private static byte[] columnDefinition(String catalog, String schema, String table, String originalTable,
                                           String name, int collation, int type) {
        ByteArrayOutputStream payload = new ByteArrayOutputStream();
        writeLengthEncodedString(payload, catalog);
        writeLengthEncodedString(payload, schema);
        writeLengthEncodedString(payload, table);
        writeLengthEncodedString(payload, originalTable);
        writeLengthEncodedString(payload, name);
        writeLengthEncodedString(payload, name);
        payload.write(0x0C);
        payload.write(collation & 0xFF);
        payload.write((collation >> 8) & 0xFF);
        payload.writeBytes(new byte[]{0x00, 0x00, 0x00, 0x00});
        payload.write(type);
        payload.writeBytes(new byte[]{0x00, 0x00});
        payload.write(0x00);
        payload.writeBytes(new byte[]{0x00, 0x00});
        return payload.toByteArray();
    }

    private static void writeLengthEncodedString(ByteArrayOutputStream payload, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        payload.write(bytes.length);
        payload.writeBytes(bytes);
    }
}
