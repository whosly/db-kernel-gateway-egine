package com.whosly.gateway.adapter.postgresql;

import com.whosly.gateway.masking.ColumnMetadata;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PostgreSQLColumnMetadataTest {

    @Test
    void mapsTextColumnsToTextMetadata() {
        byte[] payload = PostgreSQLColumnMetadata.payload(List.of(
                field("email", PostgreSQLTypeOid.VARCHAR.getOid()),
                field("age", PostgreSQLTypeOid.INT4.getOid())));

        List<ColumnMetadata> columns = PostgreSQLColumnMetadata.parse(payload, 0, payload.length);

        assertThat(columns).hasSize(2);
        assertThat(columns.get(0).name()).isEqualTo("email");
        assertThat(columns.get(0).typeName()).isEqualTo("varchar");
        assertThat(columns.get(0).category()).isEqualTo(ColumnMetadata.Category.TEXT);
        assertThat(columns.get(0).format()).isEqualTo(ColumnMetadata.ValueFormat.TEXT);
        assertThat(columns.get(1).category()).isEqualTo(ColumnMetadata.Category.NUMERIC);
    }

    @Test
    void readsThePerColumnFormatCode() {
        byte[] payload = PostgreSQLColumnMetadata.payload(List.of(
                new PostgreSQLColumnMetadata.Field("payload", 0, PostgreSQLTypeOid.BYTEA.getOid(), 1),
                new PostgreSQLColumnMetadata.Field("note", 0, PostgreSQLTypeOid.TEXT.getOid(), 0)));

        List<ColumnMetadata> columns = PostgreSQLColumnMetadata.parse(payload, 0, payload.length);

        assertThat(columns.get(0).format()).isEqualTo(ColumnMetadata.ValueFormat.BINARY);
        assertThat(columns.get(0).category()).isEqualTo(ColumnMetadata.Category.BINARY);
        assertThat(columns.get(1).format()).isEqualTo(ColumnMetadata.ValueFormat.TEXT);
    }

    @Test
    void classifiesCategoriesFromTheTypeOidTable() {
        assertThat(PostgreSQLColumnMetadata.categoryOf(PostgreSQLTypeOid.BOOL.getOid()))
                .isEqualTo(ColumnMetadata.Category.BOOLEAN);
        assertThat(PostgreSQLColumnMetadata.categoryOf(PostgreSQLTypeOid.TIMESTAMPTZ.getOid()))
                .isEqualTo(ColumnMetadata.Category.TEMPORAL);
        assertThat(PostgreSQLColumnMetadata.categoryOf(PostgreSQLTypeOid.NUMERIC.getOid()))
                .isEqualTo(ColumnMetadata.Category.NUMERIC);
        assertThat(PostgreSQLColumnMetadata.categoryOf(PostgreSQLTypeOid.JSONB.getOid()))
                .isEqualTo(ColumnMetadata.Category.TEXT);
        // A uuid is text on the wire but not free-form: a text rule must not claim it.
        assertThat(PostgreSQLColumnMetadata.categoryOf(PostgreSQLTypeOid.UUID.getOid()))
                .isEqualTo(ColumnMetadata.Category.OTHER);
        assertThat(PostgreSQLColumnMetadata.categoryOf(PostgreSQLTypeOid.TEXT_ARRAY.getOid()))
                .isEqualTo(ColumnMetadata.Category.OTHER);
    }

    @Test
    void keepsUnknownTypeOidsIdentifiable() {
        byte[] payload = PostgreSQLColumnMetadata.payload(List.of(
                new PostgreSQLColumnMetadata.Field("custom", 0, 987654, 0)));

        List<ColumnMetadata> columns = PostgreSQLColumnMetadata.parse(payload, 0, payload.length);

        assertThat(columns).singleElement().satisfies(column -> {
            assertThat(column.typeName()).isEqualTo("oid:987654");
            assertThat(column.category()).isEqualTo(ColumnMetadata.Category.OTHER);
        });
    }

    @Test
    void doesNotInventATableName() {
        byte[] payload = PostgreSQLColumnMetadata.payload(List.of(field("email", 25)));

        List<ColumnMetadata> columns = PostgreSQLColumnMetadata.parse(payload, 0, payload.length);

        // RowDescription carries a table OID, not a name; an empty name is honest.
        assertThat(columns.get(0).tableName()).isEmpty();
    }

    @Test
    void reportsNullableBecauseTheWireCarriesNoNullability() {
        byte[] payload = PostgreSQLColumnMetadata.payload(List.of(field("email", 25)));

        List<ColumnMetadata> columns = PostgreSQLColumnMetadata.parse(payload, 0, payload.length);

        assertThat(columns.get(0).nullable()).isTrue();
    }

    @Test
    void dropsFieldsThatCannotBeParsed() {
        byte[] complete = PostgreSQLColumnMetadata.payload(List.of(
                field("first", 25), field("second", 25)));
        byte[] truncated = java.util.Arrays.copyOf(complete, complete.length - 6);

        List<ColumnMetadata> columns = PostgreSQLColumnMetadata.parse(truncated, 0, truncated.length);

        // The caller compares this count with the declared one and refuses the set.
        assertThat(columns).hasSize(1);
        assertThat(columns.get(0).name()).isEqualTo("first");
    }

    @Test
    void reportsAMissingFieldCount() {
        assertThat(PostgreSQLColumnMetadata.parse(new byte[]{0x00}, 0, 1)).isEmpty();
        assertThat(PostgreSQLColumnMetadata.parse(new byte[0], 0, 0)).isEmpty();
    }

    @Test
    void readsAFieldNameAsUtf8() {
        byte[] payload = PostgreSQLColumnMetadata.payload(List.of(field("邮箱", 25)));

        List<ColumnMetadata> columns = PostgreSQLColumnMetadata.parse(payload, 0, payload.length);

        assertThat(columns.get(0).name()).isEqualTo("邮箱");
        assertThat("邮箱".getBytes(StandardCharsets.UTF_8)).isNotEmpty();
    }

    private static PostgreSQLColumnMetadata.Field field(String name, int typeOid) {
        return new PostgreSQLColumnMetadata.Field(name, 0, typeOid, 0);
    }
}
