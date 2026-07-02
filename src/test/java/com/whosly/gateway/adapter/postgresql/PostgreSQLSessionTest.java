package com.whosly.gateway.adapter.postgresql;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PostgreSQLSessionTest {

    @Test
    void tracksParametersTransactionStatusAndPreparedStatements() {
        PostgreSQLSession session = new PostgreSQLSession("pg-1");

        session.setParameter("client_encoding", "UTF8");
        session.setTransactionStatus(PostgreSQLSession.TransactionStatus.IN_TRANSACTION);
        session.putPreparedStatement("stmt1", "select 1");

        assertThat(session.getParameter("client_encoding")).contains("UTF8");
        assertThat(session.getReadyForQueryStatus()).isEqualTo('T');
        assertThat(session.getPreparedStatement("stmt1")).contains("select 1");
    }
}
