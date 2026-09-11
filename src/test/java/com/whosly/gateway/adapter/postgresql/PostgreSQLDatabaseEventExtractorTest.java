package com.whosly.gateway.adapter.postgresql;

import com.whosly.gateway.adapter.protocol.DatabaseTrafficEvent;
import com.whosly.gateway.adapter.protocol.ProtocolConnectionState;
import com.whosly.gateway.adapter.protocol.TrafficDirection;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PostgreSQLDatabaseEventExtractorTest {

    private final PostgreSQLDatabaseEventExtractor extractor =
            new PostgreSQLDatabaseEventExtractor("PostgreSQL", "pg-test");

    @Test
    void extractsSimpleQuerySql() {
        byte[] message = typedMessage('Q', cstring("select 1"));

        List<DatabaseTrafficEvent> events = extractor.extract(message, 0, message.length);

        assertThat(events).singleElement()
                .satisfies(event -> {
                    assertThat(event.getProtocolName()).isEqualTo("PostgreSQL");
                    assertThat(event.getSessionId()).isEqualTo("pg-test");
                    assertThat(event.getOperation()).isEqualTo("QUERY");
                    assertThat(event.getStatement()).isEqualTo("select 1");
                });
    }

    @Test
    void tracksParseBindExecuteToRecordPreparedSqlExecution() {
        byte[] parse = typedMessage('P',
                cstring("stmt1"),
                cstring("select * from account where id = $1"),
                shortBytes(0));
        byte[] bind = typedMessage('B',
                cstring("portal1"),
                cstring("stmt1"),
                shortBytes(0),
                shortBytes(0),
                shortBytes(0));
        byte[] execute = typedMessage('E',
                cstring("portal1"),
                intBytes(0));
        byte[] messages = concat(parse, bind, execute);

        List<DatabaseTrafficEvent> events = extractor.extract(messages, 0, messages.length);

        assertThat(events).hasSize(2);
        assertThat(events.get(0).getOperation()).isEqualTo("PARSE");
        assertThat(events.get(0).getStatement()).isEqualTo("select * from account where id = $1");
        assertThat(events.get(0).getAttribute("statementName")).contains("stmt1");
        assertThat(events.get(1).getOperation()).isEqualTo("EXECUTE");
        assertThat(events.get(1).getStatement()).isEqualTo("select * from account where id = $1");
        assertThat(events.get(1).getAttribute("portalName")).contains("portal1");
    }

    @Test
    void buffersPartialTypedMessagesUntilComplete() {
        byte[] message = typedMessage('Q', cstring("select 1"));

        assertThat(extractor.extract(message, 0, 2)).isEmpty();
        List<DatabaseTrafficEvent> events = extractor.extract(message, 2, message.length - 2);

        assertThat(events).singleElement()
                .extracting(DatabaseTrafficEvent::getStatement)
                .isEqualTo("select 1");
    }

    @Test
    void authenticationAwareExtractorSkipsStartupMessageBeforeObservingSql() {
        PostgreSQLDatabaseEventExtractor authenticationAwareExtractor =
                new PostgreSQLDatabaseEventExtractor("PostgreSQL", "pg-auth", false);
        byte[] startup = new byte[]{0x00, 0x00, 0x00, 0x08, 0x00, 0x03, 0x00, 0x02};

        assertThat(authenticationAwareExtractor.inspect(TrafficDirection.CLIENT_TO_TARGET,
                startup, 0, startup.length)).isEmpty();

        byte[] query = typedMessage('Q', cstring("select 1"));
        List<DatabaseTrafficEvent> events = authenticationAwareExtractor.inspect(TrafficDirection.CLIENT_TO_TARGET,
                query, 0, query.length);

        assertThat(events).singleElement()
                .extracting(DatabaseTrafficEvent::getStatement)
                .isEqualTo("select 1");
    }

    @Test
    void authenticationAwareExtractorStopsSqlObservationWhenServerAcceptsSsl() {
        PostgreSQLDatabaseEventExtractor authenticationAwareExtractor =
                new PostgreSQLDatabaseEventExtractor("PostgreSQL", "pg-tls", false);
        byte[] sslRequest = ByteBuffer.allocate(8)
                .putInt(8)
                .putInt(80877103)
                .array();

        assertThat(authenticationAwareExtractor.inspect(TrafficDirection.CLIENT_TO_TARGET,
                sslRequest, 0, sslRequest.length)).isEmpty();
        assertThat(authenticationAwareExtractor.inspect(TrafficDirection.TARGET_TO_CLIENT,
                new byte[]{'S'}, 0, 1)).isEmpty();

        byte[] query = typedMessage('Q', cstring("select 1"));
        assertThat(authenticationAwareExtractor.inspect(TrafficDirection.CLIENT_TO_TARGET,
                query, 0, query.length)).isEmpty();
    }

    @Test
    void authenticationAwareExtractorContinuesCleartextObservationWhenServerRejectsSsl() {
        PostgreSQLDatabaseEventExtractor authenticationAwareExtractor =
                new PostgreSQLDatabaseEventExtractor("PostgreSQL", "pg-cleartext-after-ssl-reject", false);
        byte[] sslRequest = ByteBuffer.allocate(8)
                .putInt(8)
                .putInt(80877103)
                .array();

        assertThat(authenticationAwareExtractor.inspect(TrafficDirection.CLIENT_TO_TARGET,
                sslRequest, 0, sslRequest.length)).isEmpty();
        assertThat(authenticationAwareExtractor.inspect(TrafficDirection.TARGET_TO_CLIENT,
                new byte[]{'N'}, 0, 1)).isEmpty();

        byte[] startup = new byte[]{0x00, 0x00, 0x00, 0x08, 0x00, 0x03, 0x00, 0x02};
        assertThat(authenticationAwareExtractor.inspect(TrafficDirection.CLIENT_TO_TARGET,
                startup, 0, startup.length)).isEmpty();

        byte[] query = typedMessage('Q', cstring("select 1"));
        List<DatabaseTrafficEvent> events = authenticationAwareExtractor.inspect(TrafficDirection.CLIENT_TO_TARGET,
                query, 0, query.length);

        assertThat(events).singleElement()
                .extracting(DatabaseTrafficEvent::getStatement)
                .isEqualTo("select 1");
    }

    @Test
    void recognizesCancelRequestWithoutTreatingItAsStartupOrQuery() {
        PostgreSQLDatabaseEventExtractor cancelExtractor =
                new PostgreSQLDatabaseEventExtractor("PostgreSQL", "pg-cancel", false);
        byte[] cancelRequest = ByteBuffer.allocate(16)
                .putInt(16)
                .putInt(PostgreSQLFrameCodec.CANCEL_REQUEST_CODE)
                .putInt(4711)
                .putInt(9911)
                .array();

        List<DatabaseTrafficEvent> events = cancelExtractor.inspect(TrafficDirection.CLIENT_TO_TARGET,
                cancelRequest, 0, cancelRequest.length);

        assertThat(events).isEmpty();
        assertThat(cancelExtractor.isCancelRequest()).isTrue();
    }

    @Test
    void tracksReadyForQueryTransactionStateAndMarksSessionReady() {
        PostgreSQLSession session = new PostgreSQLSession("pg-session-state");
        PostgreSQLDatabaseEventExtractor sessionExtractor =
                new PostgreSQLDatabaseEventExtractor("PostgreSQL", "pg-session-state", false, session);
        byte[] startup = new byte[]{0x00, 0x00, 0x00, 0x08, 0x00, 0x03, 0x00, 0x02};
        sessionExtractor.inspect(TrafficDirection.CLIENT_TO_TARGET, startup, 0, startup.length);
        assertThat(session.getState()).isEqualTo(ProtocolConnectionState.AUTHENTICATING);

        byte[] inTransaction = new byte[]{'Z', 0x00, 0x00, 0x00, 0x05, 'T'};
        sessionExtractor.inspect(TrafficDirection.TARGET_TO_CLIENT, inTransaction, 0, inTransaction.length);
        assertThat(session.getReadyForQueryStatus()).isEqualTo('T');
        assertThat(session.getState()).isEqualTo(ProtocolConnectionState.READY);

        byte[] failedTransaction = new byte[]{'Z', 0x00, 0x00, 0x00, 0x05, 'E'};
        sessionExtractor.inspect(TrafficDirection.TARGET_TO_CLIENT, failedTransaction, 0, failedTransaction.length);
        assertThat(session.getReadyForQueryStatus()).isEqualTo('E');
    }

    @Test
    void recordsStartupParametersOnSession() {
        PostgreSQLSession session = new PostgreSQLSession("pg-startup");
        PostgreSQLDatabaseEventExtractor startupExtractor =
                new PostgreSQLDatabaseEventExtractor("PostgreSQL", "pg-startup", false, session);

        byte[] startup = startupMessage(
                "user", "postgres",
                "database", "demo",
                "application_name", "psql");
        startupExtractor.inspect(TrafficDirection.CLIENT_TO_TARGET, startup, 0, startup.length);

        assertThat(session.getParameter("user")).contains("postgres");
        assertThat(session.getParameter("database")).contains("demo");
        assertThat(session.getParameter("application_name")).contains("psql");
        assertThat(session.getAttribute("client.user")).contains("postgres");
        assertThat(session.getAttribute("client.database")).contains("demo");
    }

    @Test
    void countsResultRowsAndCopyData() {
        PostgreSQLSession session = new PostgreSQLSession("pg-row-counts");
        PostgreSQLDatabaseEventExtractor countsExtractor =
                new PostgreSQLDatabaseEventExtractor("PostgreSQL", "pg-row-counts", true, session);

        inspectBackend(countsExtractor, 'T', shortBytes(2));
        inspectBackend(countsExtractor, 'D', shortBytes(1), new byte[]{0x01, 'a'});
        inspectBackend(countsExtractor, 'D', shortBytes(1), new byte[]{0x01, 'b'});
        inspectBackend(countsExtractor, 'C', cstring("SELECT 2"));

        assertThat(session.getLastResultRowCount()).isEqualTo(2);

        inspectBackend(countsExtractor, 'G', new byte[]{0x00});
        inspectBackend(countsExtractor, 'd', new byte[]{0x01, 'x'});
        inspectBackend(countsExtractor, 'd', new byte[]{0x01, 'y'});
        inspectBackend(countsExtractor, 'c');

        assertThat(session.getLastCopyDataCount()).isEqualTo(2);
    }

    @Test
    void movesSessionToClosingOnTerminate() {
        PostgreSQLSession session = new PostgreSQLSession("pg-terminate");
        PostgreSQLDatabaseEventExtractor terminateExtractor =
                new PostgreSQLDatabaseEventExtractor("PostgreSQL", "pg-terminate", true, session);

        byte[] terminate = typedMessage('X');
        assertThat(terminateExtractor.inspect(TrafficDirection.CLIENT_TO_TARGET,
                terminate, 0, terminate.length)).isEmpty();

        assertThat(session.getState()).isEqualTo(ProtocolConnectionState.CLOSING);
    }

    @Test
    void observesExtendedQueryAcknowledgementsSyncAndNotifications() {
        PostgreSQLSession session = new PostgreSQLSession("pg-extended");
        PostgreSQLDatabaseEventExtractor extendedExtractor =
                new PostgreSQLDatabaseEventExtractor("PostgreSQL", "pg-extended", false, session);

        byte[] startup = new byte[]{0x00, 0x00, 0x00, 0x08, 0x00, 0x03, 0x00, 0x02};
        extendedExtractor.inspect(TrafficDirection.CLIENT_TO_TARGET, startup, 0, startup.length);
        inspectBackend(extendedExtractor, 'Z', new byte[]{'I'});
        assertThat(session.getState()).isEqualTo(ProtocolConnectionState.READY);

        byte[] parse = typedMessage('P', cstring("stmt1"), cstring("select 1"), shortBytes(0));
        byte[] bind = typedMessage('B', cstring("portal1"), cstring("stmt1"),
                shortBytes(0), shortBytes(0), shortBytes(0));
        byte[] sync = typedMessage('S');
        byte[] batch = concat(parse, bind, sync);
        extendedExtractor.extract(batch, 0, batch.length);

        assertThat(session.isSyncPending()).isTrue();
        assertThat(session.getState()).isEqualTo(ProtocolConnectionState.EXECUTING);

        inspectBackend(extendedExtractor, '1');
        inspectBackend(extendedExtractor, '2');
        inspectBackend(extendedExtractor, 'n');
        inspectBackend(extendedExtractor, 's');
        inspectBackend(extendedExtractor, '3');
        inspectBackend(extendedExtractor, 'A', intBytes(4711), cstring("events"), cstring("payload"));
        inspectBackend(extendedExtractor, 'Z', new byte[]{'I'});

        assertThat(session.getParseCompleteCount()).isEqualTo(1);
        assertThat(session.getBindCompleteCount()).isEqualTo(1);
        assertThat(session.getNoDataCount()).isEqualTo(1);
        assertThat(session.getPortalSuspendedCount()).isEqualTo(1);
        assertThat(session.getCloseCompleteCount()).isEqualTo(1);
        assertThat(session.getLastNotificationProcessId()).isEqualTo(4711);
        assertThat(session.getLastNotificationChannel()).contains("events");
        assertThat(session.isSyncPending()).isFalse();
        assertThat(session.getSyncCount()).isEqualTo(1);
        assertThat(session.getState()).isEqualTo(ProtocolConnectionState.READY);
    }

    @Test
    void associatesCancelRequestWithTheSessionThatOwnsTheKey() {
        PostgreSQLCancelKeyRegistry registry = new PostgreSQLCancelKeyRegistry();
        PostgreSQLSession session = new PostgreSQLSession("pg-cancel-target");
        PostgreSQLDatabaseEventExtractor sessionExtractor = new PostgreSQLDatabaseEventExtractor(
                "PostgreSQL", "pg-cancel-target", true, session, registry);

        inspectBackend(sessionExtractor, 'K', intBytes(4711), intBytes(9911));
        assertThat(registry.size()).isEqualTo(1);

        PostgreSQLDatabaseEventExtractor cancelExtractor = new PostgreSQLDatabaseEventExtractor(
                "PostgreSQL", "pg-cancel-request", false, null, registry);
        byte[] cancelRequest = ByteBuffer.allocate(16)
                .putInt(16)
                .putInt(PostgreSQLFrameCodec.CANCEL_REQUEST_CODE)
                .putInt(4711)
                .putInt(9911)
                .array();

        assertThat(cancelExtractor.inspect(TrafficDirection.CLIENT_TO_TARGET,
                cancelRequest, 0, cancelRequest.length)).isEmpty();

        assertThat(cancelExtractor.getCancelTargetSessionId()).contains("pg-cancel-target");
        assertThat(cancelExtractor.getCancelRequestProcessId()).isEqualTo(4711);

        registry.unregister("pg-cancel-target");
        assertThat(registry.size()).isZero();
    }

    @Test
    void tracksNamedPreparedStatementsAcrossParseAndClose() {
        PostgreSQLSession session = new PostgreSQLSession("pg-prepared");
        PostgreSQLDatabaseEventExtractor preparedExtractor =
                new PostgreSQLDatabaseEventExtractor("PostgreSQL", "pg-prepared", true, session);

        byte[] parse = typedMessage('P', cstring("stmt1"), cstring("select 1"), shortBytes(0));
        preparedExtractor.extract(parse, 0, parse.length);
        assertThat(session.getOpenPreparedStatements()).isEqualTo(1);

        // Re-parsing the same name replaces the statement instead of adding one.
        preparedExtractor.extract(parse, 0, parse.length);
        assertThat(session.getOpenPreparedStatements()).isEqualTo(1);

        // The unnamed statement is overwritten by the next Parse and never accumulates.
        byte[] unnamed = typedMessage('P', cstring(""), cstring("select 2"), shortBytes(0));
        preparedExtractor.extract(unnamed, 0, unnamed.length);
        assertThat(session.getOpenPreparedStatements()).isEqualTo(1);

        byte[] close = typedMessage('C', cstring("S"), cstring("stmt1"));
        preparedExtractor.extract(close, 0, close.length);

        assertThat(session.getOpenPreparedStatements()).isZero();
        assertThat(session.getDirtiness().isClean()).isTrue();
    }

    private static byte[] startupMessage(String... keyValues) {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        body.writeBytes(intBytes(196608));
        for (int index = 0; index + 1 < keyValues.length; index += 2) {
            body.writeBytes(cstring(keyValues[index]));
            body.writeBytes(cstring(keyValues[index + 1]));
        }
        body.write(0);
        byte[] payload = body.toByteArray();
        return concat(intBytes(payload.length + 4), payload);
    }

    @Test
    void observesBackendCommandTagRowCountAndSqlState() {
        PostgreSQLSession session = new PostgreSQLSession("pg-backend");
        PostgreSQLDatabaseEventExtractor backendExtractor =
                new PostgreSQLDatabaseEventExtractor("PostgreSQL", "pg-backend", true, session);

        byte[] rowDescription = typedMessage('T', shortBytes(3));
        backendExtractor.inspect(TrafficDirection.TARGET_TO_CLIENT, rowDescription, 0, rowDescription.length);

        byte[] commandComplete = typedMessage('C', cstring("SELECT 3"));
        backendExtractor.inspect(TrafficDirection.TARGET_TO_CLIENT, commandComplete, 0, commandComplete.length);

        byte[] errorResponse = typedMessage('E',
                errorField('S', "ERROR"), errorField('C', "42601"), new byte[]{0});
        backendExtractor.inspect(TrafficDirection.TARGET_TO_CLIENT, errorResponse, 0, errorResponse.length);

        assertThat(session.getLastRowDescriptionFieldCount()).isEqualTo(3);
        assertThat(session.getLastCommandTag()).contains("SELECT 3");
        assertThat(session.getLastSqlState()).contains("42601");
        assertThat(session.getLastSeverity()).contains("ERROR");
    }

    @Test
    void observesBackendParametersKeyDataAuthenticationAndNotices() {
        PostgreSQLSession session = new PostgreSQLSession("pg-backend-info");
        PostgreSQLDatabaseEventExtractor backendExtractor =
                new PostgreSQLDatabaseEventExtractor("PostgreSQL", "pg-backend-info", true, session);

        inspectBackend(backendExtractor, 'S', cstring("server_version"), cstring("18.0"));
        inspectBackend(backendExtractor, 'K', intBytes(4711), intBytes(9911));
        inspectBackend(backendExtractor, 'N',
                errorField('S', "WARNING"), errorField('C', "01000"), new byte[]{0});
        inspectBackend(backendExtractor, 'R', intBytes(10));
        inspectBackend(backendExtractor, 't', shortBytes(2));

        assertThat(session.getParameter("server_version")).contains("18.0");
        assertThat(session.getBackendProcessId()).isEqualTo(4711);
        assertThat(session.getBackendSecretKey()).isEqualTo(9911);
        assertThat(session.getLastNoticeSeverity()).contains("WARNING");
        assertThat(session.getLastNoticeSqlState()).contains("01000");
        assertThat(session.getLastAuthenticationType()).isEqualTo(10);
        assertThat(session.getLastParameterDescriptionCount()).isEqualTo(2);
    }

    @Test
    void closeRemovesPortalMappingSoExecuteIsNoLongerReported() {
        PostgreSQLDatabaseEventExtractor closeExtractor =
                new PostgreSQLDatabaseEventExtractor("PostgreSQL", "pg-close", true);

        byte[] parse = typedMessage('P', cstring("stmt1"), cstring("select 1"), shortBytes(0));
        byte[] bind = typedMessage('B', cstring("portal1"), cstring("stmt1"),
                shortBytes(0), shortBytes(0), shortBytes(0));
        byte[] setup = concat(parse, bind);
        closeExtractor.extract(setup, 0, setup.length);

        byte[] execute = typedMessage('E', cstring("portal1"), intBytes(0));
        assertThat(closeExtractor.extract(execute, 0, execute.length)).singleElement()
                .extracting(DatabaseTrafficEvent::getStatement).isEqualTo("select 1");

        byte[] close = typedMessage('C', cstring("P"), cstring("portal1"));
        closeExtractor.extract(close, 0, close.length);

        assertThat(closeExtractor.extract(execute, 0, execute.length)).isEmpty();
    }

    @Test
    void entersStreamingStateDuringCopyAndReturnsToReady() {
        PostgreSQLSession session = new PostgreSQLSession("pg-copy");
        PostgreSQLDatabaseEventExtractor copyExtractor =
                new PostgreSQLDatabaseEventExtractor("PostgreSQL", "pg-copy", false, session);

        byte[] startup = new byte[]{0x00, 0x00, 0x00, 0x08, 0x00, 0x03, 0x00, 0x02};
        copyExtractor.inspect(TrafficDirection.CLIENT_TO_TARGET, startup, 0, startup.length);
        inspectBackend(copyExtractor, 'Z', new byte[]{'I'});
        assertThat(session.getState()).isEqualTo(ProtocolConnectionState.READY);

        inspectBackend(copyExtractor, 'G', new byte[]{0x00});
        assertThat(session.getState()).isEqualTo(ProtocolConnectionState.STREAMING);

        inspectBackend(copyExtractor, 'Z', new byte[]{'I'});
        assertThat(session.getState()).isEqualTo(ProtocolConnectionState.READY);
    }

    private static void inspectBackend(PostgreSQLDatabaseEventExtractor extractor, char type, byte[]... bodies) {
        byte[] message = typedMessage(type, bodies);
        extractor.inspect(TrafficDirection.TARGET_TO_CLIENT, message, 0, message.length);
    }

    private static byte[] errorField(char code, String value) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        output.write(code);
        output.writeBytes(cstring(value));
        return output.toByteArray();
    }

    private static byte[] typedMessage(char type, byte[]... bodies) {
        byte[] body = concat(bodies);
        ByteBuffer buffer = ByteBuffer.allocate(1 + 4 + body.length);
        buffer.put((byte) type);
        buffer.putInt(body.length + 4);
        buffer.put(body);
        return buffer.array();
    }

    private static byte[] cstring(String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        byte[] cstring = new byte[bytes.length + 1];
        System.arraycopy(bytes, 0, cstring, 0, bytes.length);
        return cstring;
    }

    private static byte[] shortBytes(int value) {
        return ByteBuffer.allocate(2).putShort((short) value).array();
    }

    private static byte[] intBytes(int value) {
        return ByteBuffer.allocate(4).putInt(value).array();
    }

    private static byte[] concat(byte[]... arrays) {
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        for (byte[] array : arrays) {
            outputStream.writeBytes(array);
        }
        return outputStream.toByteArray();
    }
}
