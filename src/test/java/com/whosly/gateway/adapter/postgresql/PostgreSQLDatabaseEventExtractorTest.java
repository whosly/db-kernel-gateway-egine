package com.whosly.gateway.adapter.postgresql;

import com.whosly.gateway.adapter.protocol.DatabaseTrafficEvent;
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
