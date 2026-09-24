package com.whosly.gateway.adapter.protocol;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MutableDatabaseRiskPolicyTest {

    @Test
    void hotSwapAppliesToSubsequentEvaluate() {
        MutableDatabaseRiskPolicy mutable = new MutableDatabaseRiskPolicy();
        DatabaseTrafficEvent drop = DatabaseTrafficEvent.builder(
                "MySQL", "s1", "QUERY", "DROP TABLE t").build();

        assertThat(mutable.evaluate(drop).isAllowed()).isTrue();

        mutable.replace(DenyListDatabaseRiskPolicy.of(List.of(), List.of("drop table")));
        assertThat(mutable.evaluate(drop).isAllowed()).isFalse();

        mutable.replace(DatabaseRiskPolicy.allowAll());
        assertThat(mutable.evaluate(drop).isAllowed()).isTrue();
    }
}
