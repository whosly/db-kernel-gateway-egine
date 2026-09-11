package com.whosly.gateway.masking;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class ColumnSelectorTest {

    @Test
    void matchesByExactNameIgnoringCase() {
        assertThat(ColumnSelector.named("email").matches(ColumnMetadata.text("EMAIL"))).isTrue();
        assertThat(ColumnSelector.named("email").matches(ColumnMetadata.text("e_mail"))).isFalse();
    }

    @Test
    void matchesByNamePattern() {
        assertThat(ColumnSelector.namePattern(".*_id").matches(ColumnMetadata.text("user_id"))).isTrue();
        assertThat(ColumnSelector.namePattern(".*_id").matches(ColumnMetadata.text("id"))).isFalse();
    }

    @Test
    void matchesByTableWhenTheMetadataCarriesIt() {
        ColumnMetadata inTable = new ColumnMetadata("email", Optional.of("account"), "text",
                ColumnMetadata.Category.TEXT, ColumnMetadata.ValueFormat.TEXT, true);

        assertThat(ColumnSelector.inTable("ACCOUNT").matches(inTable)).isTrue();
        assertThat(ColumnSelector.inTable("account").matches(ColumnMetadata.text("email"))).isFalse();
    }

    @Test
    void composesWithAndOr() {
        ColumnSelector either = ColumnSelector.named("email").or(ColumnSelector.named("phone"));

        assertThat(either.matches(ColumnMetadata.text("phone"))).isTrue();
        assertThat(either.matches(ColumnMetadata.text("name"))).isFalse();
        assertThat(ColumnSelector.named("email").and(ColumnSelector.namePattern("e.*"))
                .matches(ColumnMetadata.text("email"))).isTrue();
        assertThat(ColumnSelector.all().matches(ColumnMetadata.text("anything"))).isTrue();
    }
}
