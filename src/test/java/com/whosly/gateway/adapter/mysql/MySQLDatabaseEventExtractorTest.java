package com.whosly.gateway.adapter.mysql;

import com.whosly.gateway.adapter.protocol.DatabaseTrafficEvent;
import com.whosly.gateway.adapter.protocol.ObservationConfidence;
import com.whosly.gateway.adapter.protocol.ProtocolConnectionState;
import com.whosly.gateway.adapter.protocol.TrafficDirection;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MySQLDatabaseEventExtractorTest {

    private final MySQLDatabaseEventExtractor extractor = new MySQLDatabaseEventExtractor("MySQL", "mysql-test");

    @Test
    void extractsComQuerySqlFromMySqlPacket() {
        byte[] packet = packet(0, MySQLCommandType.COM_QUERY.getCode(), "select 1");

        List<DatabaseTrafficEvent> events = extractor.extract(packet, 0, packet.length);

        assertThat(events).hasSize(1);
        assertThat(events.get(0).getProtocolName()).isEqualTo("MySQL");
        assertThat(events.get(0).getSessionId()).isEqualTo("mysql-test");
        assertThat(events.get(0).getOperation()).isEqualTo("COM_QUERY");
        assertThat(events.get(0).getStatement()).isEqualTo("select 1");
    }

    @Test
    void extractsComQuerySqlWhenClientSendsQueryAttributeCounts() {
        MySQLDatabaseEventExtractor queryAttributeExtractor =
                new MySQLDatabaseEventExtractor("MySQL", "mysql-query-attributes", false);
        byte[] handshakeResponse = rawPacket(1,
                capabilityPayload(MySQLCapability.CLIENT_QUERY_ATTRIBUTES.getFlag()
                        | MySQLCapability.CLIENT_PROTOCOL_41.getFlag()));
        queryAttributeExtractor.inspect(TrafficDirection.CLIENT_TO_TARGET,
                handshakeResponse, 0, handshakeResponse.length);
        byte[] okPacket = rawPacket(2, new byte[]{0x00});
        queryAttributeExtractor.inspect(TrafficDirection.TARGET_TO_CLIENT, okPacket, 0, okPacket.length);

        byte[] sqlBytes = "select 1".getBytes(StandardCharsets.UTF_8);
        byte[] payload = new byte[1 + 2 + sqlBytes.length];
        payload[0] = (byte) MySQLCommandType.COM_QUERY.getCode();
        payload[1] = 0;
        payload[2] = 1;
        System.arraycopy(sqlBytes, 0, payload, 3, sqlBytes.length);
        byte[] packet = rawPacket(0, payload);

        List<DatabaseTrafficEvent> events = queryAttributeExtractor.inspect(TrafficDirection.CLIENT_TO_TARGET,
                packet, 0, packet.length);

        assertThat(events).singleElement()
                .extracting(DatabaseTrafficEvent::getStatement)
                .isEqualTo("select 1");
    }

    @Test
    void authenticationAwareExtractorReadsCapabilitiesFromSplitHandshakeResponse() {
        MySQLDatabaseEventExtractor queryAttributeExtractor =
                new MySQLDatabaseEventExtractor("MySQL", "mysql-split-handshake", false);
        byte[] handshakeResponse = rawPacket(1,
                capabilityPayload(MySQLCapability.CLIENT_QUERY_ATTRIBUTES.getFlag()
                        | MySQLCapability.CLIENT_PROTOCOL_41.getFlag()));

        assertThat(queryAttributeExtractor.inspect(TrafficDirection.CLIENT_TO_TARGET,
                handshakeResponse, 0, 2)).isEmpty();
        assertThat(queryAttributeExtractor.inspect(TrafficDirection.CLIENT_TO_TARGET,
                handshakeResponse, 2, handshakeResponse.length - 2)).isEmpty();
        assertThat(queryAttributeExtractor.inspect(TrafficDirection.TARGET_TO_CLIENT,
                rawPacket(2, new byte[]{0x00}), 0, 5)).isEmpty();

        byte[] sqlBytes = "select 1".getBytes(StandardCharsets.UTF_8);
        byte[] payload = new byte[1 + 2 + sqlBytes.length];
        payload[0] = (byte) MySQLCommandType.COM_QUERY.getCode();
        payload[1] = 0;
        payload[2] = 1;
        System.arraycopy(sqlBytes, 0, payload, 3, sqlBytes.length);

        List<DatabaseTrafficEvent> events = queryAttributeExtractor.inspect(TrafficDirection.CLIENT_TO_TARGET,
                rawPacket(0, payload), 0, payload.length + 4);

        assertThat(events).singleElement()
                .extracting(DatabaseTrafficEvent::getStatement)
                .isEqualTo("select 1");
    }

    @Test
    void extractsComQuerySqlWhenQueryAttributesContainParameterMetadataAndValues() {
        MySQLDatabaseEventExtractor queryAttributeExtractor =
                new MySQLDatabaseEventExtractor("MySQL", "mysql-query-attributes", false);
        byte[] handshakeResponse = rawPacket(1,
                capabilityPayload(MySQLCapability.CLIENT_QUERY_ATTRIBUTES.getFlag()
                        | MySQLCapability.CLIENT_PROTOCOL_41.getFlag()));
        queryAttributeExtractor.inspect(TrafficDirection.CLIENT_TO_TARGET,
                handshakeResponse, 0, handshakeResponse.length);
        queryAttributeExtractor.inspect(TrafficDirection.TARGET_TO_CLIENT,
                rawPacket(2, new byte[]{0x00}), 0, 5);

        byte[] sqlBytes = "select @a, @b".getBytes(StandardCharsets.UTF_8);
        byte[] payload = new byte[]{
                (byte) MySQLCommandType.COM_QUERY.getCode(),
                0x02,
                0x01,
                0x00,
                0x01,
                0x08, 0x00, 0x01, 'a',
                (byte) 0xfd, 0x00, 0x01, 'b',
                0x2a, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
                0x03, 'f', 'o', 'o'
        };
        byte[] packetPayload = new byte[payload.length + sqlBytes.length];
        System.arraycopy(payload, 0, packetPayload, 0, payload.length);
        System.arraycopy(sqlBytes, 0, packetPayload, payload.length, sqlBytes.length);

        List<DatabaseTrafficEvent> events = queryAttributeExtractor.inspect(TrafficDirection.CLIENT_TO_TARGET,
                rawPacket(0, packetPayload), 0, packetPayload.length + 4);

        assertThat(events).singleElement()
                .extracting(DatabaseTrafficEvent::getStatement)
                .isEqualTo("select @a, @b");
    }

    @Test
    void extractsPreparedStatementSqlFromComStmtPreparePacket() {
        byte[] packet = packet(0, MySQLCommandType.COM_STMT_PREPARE.getCode(),
                "select * from account where id = ?");

        List<DatabaseTrafficEvent> events = extractor.extract(packet, 0, packet.length);

        assertThat(events).singleElement()
                .satisfies(event -> {
                    assertThat(event.getOperation()).isEqualTo("COM_STMT_PREPARE");
                    assertThat(event.getStatement()).isEqualTo("select * from account where id = ?");
                });
    }

    @Test
    void buffersPartialPacketsUntilSqlPacketIsComplete() {
        byte[] packet = packet(0, MySQLCommandType.COM_QUERY.getCode(), "select 1");

        assertThat(extractor.extract(packet, 0, 3)).isEmpty();
        List<DatabaseTrafficEvent> events = extractor.extract(packet, 3, packet.length - 3);

        assertThat(events).singleElement()
                .extracting(DatabaseTrafficEvent::getStatement)
                .isEqualTo("select 1");
    }

    @Test
    void authenticationAwareExtractorSkipsHandshakeResponseBeforeObservingSql() {
        MySQLDatabaseEventExtractor authenticationAwareExtractor =
                new MySQLDatabaseEventExtractor("MySQL", "mysql-auth", false);
        byte[] credentialLikePacket = rawPacket(1, capabilityPayload(MySQLCapability.CLIENT_PROTOCOL_41.getFlag()));

        assertThat(authenticationAwareExtractor.inspect(TrafficDirection.CLIENT_TO_TARGET,
                credentialLikePacket, 0, credentialLikePacket.length)).isEmpty();

        byte[] okPacket = rawPacket(2, new byte[]{0x00});
        assertThat(authenticationAwareExtractor.inspect(TrafficDirection.TARGET_TO_CLIENT,
                okPacket, 0, okPacket.length)).isEmpty();

        byte[] query = packet(0, MySQLCommandType.COM_QUERY.getCode(), "select 1");
        List<DatabaseTrafficEvent> events = authenticationAwareExtractor.inspect(TrafficDirection.CLIENT_TO_TARGET,
                query, 0, query.length);

        assertThat(events).singleElement()
                .extracting(DatabaseTrafficEvent::getStatement)
                .isEqualTo("select 1");
    }

    @Test
    void authenticationAwareExtractorHandlesSplitAuthenticationOkPacket() {
        MySQLDatabaseEventExtractor authenticationAwareExtractor =
                new MySQLDatabaseEventExtractor("MySQL", "mysql-auth", false);
        byte[] okPacket = rawPacket(2, new byte[]{0x00});

        assertThat(authenticationAwareExtractor.inspect(TrafficDirection.TARGET_TO_CLIENT,
                okPacket, 0, 2)).isEmpty();
        assertThat(authenticationAwareExtractor.inspect(TrafficDirection.TARGET_TO_CLIENT,
                okPacket, 2, okPacket.length - 2)).isEmpty();

        byte[] query = packet(0, MySQLCommandType.COM_QUERY.getCode(), "select 1");
        List<DatabaseTrafficEvent> events = authenticationAwareExtractor.inspect(TrafficDirection.CLIENT_TO_TARGET,
                query, 0, query.length);

        assertThat(events).singleElement()
                .extracting(DatabaseTrafficEvent::getStatement)
                .isEqualTo("select 1");
    }

    @Test
    void authenticationAwareExtractorStopsSqlObservationWhenClientRequestsTls() {
        MySQLDatabaseEventExtractor authenticationAwareExtractor =
                new MySQLDatabaseEventExtractor("MySQL", "mysql-tls", false);
        byte[] sslRequest = rawPacket(1, capabilityPayload(MySQLCapability.CLIENT_SSL.getFlag()));

        assertThat(authenticationAwareExtractor.inspect(TrafficDirection.CLIENT_TO_TARGET,
                sslRequest, 0, sslRequest.length)).isEmpty();

        byte[] query = packet(0, MySQLCommandType.COM_QUERY.getCode(), "select 1");
        assertThat(authenticationAwareExtractor.inspect(TrafficDirection.CLIENT_TO_TARGET,
                query, 0, query.length)).isEmpty();
    }

    @Test
    void authenticationAwareExtractorStopsSqlObservationWhenClientRequestsCompression() {
        MySQLDatabaseEventExtractor authenticationAwareExtractor =
                new MySQLDatabaseEventExtractor("MySQL", "mysql-compress", false);
        byte[] handshakeResponse = rawPacket(1, capabilityPayload(MySQLCapability.CLIENT_COMPRESS.getFlag()));

        assertThat(authenticationAwareExtractor.inspect(TrafficDirection.CLIENT_TO_TARGET,
                handshakeResponse, 0, handshakeResponse.length)).isEmpty();
        byte[] okPacket = rawPacket(2, new byte[]{0x00});
        assertThat(authenticationAwareExtractor.inspect(TrafficDirection.TARGET_TO_CLIENT,
                okPacket, 0, okPacket.length)).isEmpty();

        byte[] query = packet(0, MySQLCommandType.COM_QUERY.getCode(), "select 1");
        assertThat(authenticationAwareExtractor.inspect(TrafficDirection.CLIENT_TO_TARGET,
                query, 0, query.length)).isEmpty();
    }

    @Test
    void authenticationContinuationPayloadDoesNotDisableCleartextObservation() {
        MySQLDatabaseEventExtractor authenticationAwareExtractor =
                new MySQLDatabaseEventExtractor("MySQL", "mysql-auth-more", false);
        byte[] handshakeResponse = rawPacket(1, capabilityPayload(MySQLCapability.CLIENT_PROTOCOL_41.getFlag()));

        assertThat(authenticationAwareExtractor.inspect(TrafficDirection.CLIENT_TO_TARGET,
                handshakeResponse, 0, handshakeResponse.length)).isEmpty();

        byte[] authContinuationLooksLikeCompression = rawPacket(3,
                capabilityPayload(MySQLCapability.CLIENT_COMPRESS.getFlag()));
        assertThat(authenticationAwareExtractor.inspect(TrafficDirection.CLIENT_TO_TARGET,
                authContinuationLooksLikeCompression, 0, authContinuationLooksLikeCompression.length)).isEmpty();

        byte[] okPacket = rawPacket(4, new byte[]{0x00});
        assertThat(authenticationAwareExtractor.inspect(TrafficDirection.TARGET_TO_CLIENT,
                okPacket, 0, okPacket.length)).isEmpty();

        byte[] query = packet(0, MySQLCommandType.COM_QUERY.getCode(), "select 1");
        List<DatabaseTrafficEvent> events = authenticationAwareExtractor.inspect(TrafficDirection.CLIENT_TO_TARGET,
                query, 0, query.length);

        assertThat(events).singleElement()
                .extracting(DatabaseTrafficEvent::getStatement)
                .isEqualTo("select 1");
    }

    @Test
    void reassemblesFragmentedComQuerySplitAcrossMaximumLengthPackets() {
        MySQLDatabaseEventExtractor largePacketExtractor =
                new MySQLDatabaseEventExtractor("MySQL", "mysql-large", true);
        int maxPayload = MySQLFrameCodec.MAX_PAYLOAD_LENGTH;
        byte[] sqlBytes = new byte[maxPayload + 2];
        Arrays.fill(sqlBytes, (byte) 'a');
        byte[] logicalPayload = new byte[1 + sqlBytes.length];
        logicalPayload[0] = (byte) MySQLCommandType.COM_QUERY.getCode();
        System.arraycopy(sqlBytes, 0, logicalPayload, 1, sqlBytes.length);

        byte[] firstPacket = framedPayload(logicalPayload, 0, maxPayload, 0);
        byte[] secondPacket = framedPayload(logicalPayload, maxPayload,
                logicalPayload.length - maxPayload, 1);

        assertThat(largePacketExtractor.inspect(TrafficDirection.CLIENT_TO_TARGET,
                firstPacket, 0, firstPacket.length)).isEmpty();

        List<DatabaseTrafficEvent> events = largePacketExtractor.inspect(TrafficDirection.CLIENT_TO_TARGET,
                secondPacket, 0, secondPacket.length);

        assertThat(events).singleElement().satisfies(event -> {
            assertThat(event.getOperation()).isEqualTo("COM_QUERY");
            assertThat(event.getStatement()).hasSize(sqlBytes.length);
        });
    }

    @Test
    void advancesSessionStateFromNegotiationToReady() {
        MySQLSession session = new MySQLSession("mysql-session-state");
        MySQLDatabaseEventExtractor sessionExtractor =
                new MySQLDatabaseEventExtractor("MySQL", "mysql-session-state", false, session);

        assertThat(session.getState()).isEqualTo(ProtocolConnectionState.CONNECTED);

        byte[] handshakeResponse = rawPacket(1, capabilityPayload(MySQLCapability.CLIENT_PROTOCOL_41.getFlag()));
        sessionExtractor.inspect(TrafficDirection.CLIENT_TO_TARGET,
                handshakeResponse, 0, handshakeResponse.length);
        assertThat(session.getState()).isEqualTo(ProtocolConnectionState.AUTHENTICATING);

        byte[] okPacket = rawPacket(2, new byte[]{0x00});
        sessionExtractor.inspect(TrafficDirection.TARGET_TO_CLIENT, okPacket, 0, okPacket.length);
        assertThat(session.getState()).isEqualTo(ProtocolConnectionState.READY);
    }

    @Test
    void recordsClientIdentityAndDatabaseFromHandshakeResponse() {
        MySQLSession session = new MySQLSession("mysql-identity");
        MySQLDatabaseEventExtractor identityExtractor =
                new MySQLDatabaseEventExtractor("MySQL", "mysql-identity", false, session);

        byte[] handshakeResponse = handshakeResponse(
                MySQLCapability.CLIENT_PROTOCOL_41.getFlag()
                        | MySQLCapability.CLIENT_SECURE_CONNECTION.getFlag()
                        | MySQLCapability.CLIENT_CONNECT_WITH_DB.getFlag()
                        | MySQLCapability.CLIENT_PLUGIN_AUTH.getFlag(),
                "appuser",
                new byte[20],
                "demo",
                "mysql_native_password");

        identityExtractor.inspect(TrafficDirection.CLIENT_TO_TARGET,
                handshakeResponse, 0, handshakeResponse.length);

        assertThat(session.getAttribute("client.user")).contains("appuser");
        assertThat(session.getCurrentDatabase()).contains("demo");
    }

    @Test
    void updatesSessionDatabaseOnComInitDb() {
        MySQLSession session = new MySQLSession("mysql-init-db");
        MySQLDatabaseEventExtractor initDbExtractor =
                new MySQLDatabaseEventExtractor("MySQL", "mysql-init-db", true, session);

        byte[] initDb = packet(0, MySQLCommandType.COM_INIT_DB.getCode(), "analytics");
        List<DatabaseTrafficEvent> events = initDbExtractor.extract(initDb, 0, initDb.length);

        assertThat(events).isEmpty();
        assertThat(session.getCurrentDatabase()).contains("analytics");
    }

    @Test
    void recordsResultSetColumnCountAndRowCount() {
        MySQLSession session = new MySQLSession("mysql-result-metadata");
        MySQLDatabaseEventExtractor metadataExtractor =
                new MySQLDatabaseEventExtractor("MySQL", "mysql-result-metadata", true, session);

        sendQuery(metadataExtractor);
        inspectTarget(metadataExtractor, 1, new byte[]{0x02});
        inspectTarget(metadataExtractor, 2, new byte[]{0x03, 'i', 'd', 0x00});
        inspectTarget(metadataExtractor, 3, new byte[]{0x03, 'n', 'a', 'm', 0x00});
        inspectTarget(metadataExtractor, 4, new byte[]{(byte) 0xFE, 0x00, 0x00, 0x02, 0x00});
        inspectTarget(metadataExtractor, 5, new byte[]{0x01, '1', 0x01, 'a'});
        inspectTarget(metadataExtractor, 6, new byte[]{0x01, '2', 0x01, 'b'});
        inspectTarget(metadataExtractor, 7, new byte[]{(byte) 0xFE, 0x00, 0x00, 0x02, 0x00});

        assertThat(session.getLastResultSetColumnCount()).isEqualTo(2);
        assertThat(session.getLastResultSetRowCount()).isEqualTo(2);
    }

    @Test
    void completesCommandCycleThroughExecuting() {
        MySQLSession session = new MySQLSession("mysql-cycle");
        MySQLDatabaseEventExtractor cycleExtractor =
                new MySQLDatabaseEventExtractor("MySQL", "mysql-cycle", false, session);

        byte[] handshakeResponse = rawPacket(1, capabilityPayload(MySQLCapability.CLIENT_PROTOCOL_41.getFlag()));
        cycleExtractor.inspect(TrafficDirection.CLIENT_TO_TARGET,
                handshakeResponse, 0, handshakeResponse.length);
        inspectTarget(cycleExtractor, 2, new byte[]{0x00});
        assertThat(session.getState()).isEqualTo(ProtocolConnectionState.READY);

        byte[] query = packet(0, MySQLCommandType.COM_QUERY.getCode(), "select 1");
        cycleExtractor.inspect(TrafficDirection.CLIENT_TO_TARGET, query, 0, query.length);
        assertThat(session.getState()).isEqualTo(ProtocolConnectionState.EXECUTING);

        inspectTarget(cycleExtractor, 1, okPayload(0, 0x0002));
        assertThat(session.getState()).isEqualTo(ProtocolConnectionState.READY);
    }

    @Test
    void movesSessionToClosingOnComQuit() {
        MySQLSession session = new MySQLSession("mysql-quit");
        MySQLDatabaseEventExtractor quitExtractor =
                new MySQLDatabaseEventExtractor("MySQL", "mysql-quit", false, session);

        byte[] handshakeResponse = rawPacket(1, capabilityPayload(MySQLCapability.CLIENT_PROTOCOL_41.getFlag()));
        quitExtractor.inspect(TrafficDirection.CLIENT_TO_TARGET,
                handshakeResponse, 0, handshakeResponse.length);
        inspectTarget(quitExtractor, 2, new byte[]{0x00});
        assertThat(session.getState()).isEqualTo(ProtocolConnectionState.READY);

        byte[] quit = rawPacket(0, new byte[]{(byte) MySQLCommandType.COM_QUIT.getCode()});
        assertThat(quitExtractor.inspect(TrafficDirection.CLIENT_TO_TARGET,
                quit, 0, quit.length)).isEmpty();

        assertThat(session.getState()).isEqualTo(ProtocolConnectionState.CLOSING);
    }

    @Test
    void mirrorsIdentityAndDatabaseOnComChangeUser() {
        MySQLSession session = new MySQLSession("mysql-change-user");
        MySQLDatabaseEventExtractor changeUserExtractor =
                new MySQLDatabaseEventExtractor("MySQL", "mysql-change-user", false, session);

        byte[] handshakeResponse = rawPacket(1, capabilityPayload(
                MySQLCapability.CLIENT_PROTOCOL_41.getFlag()
                        | MySQLCapability.CLIENT_SECURE_CONNECTION.getFlag()));
        changeUserExtractor.inspect(TrafficDirection.CLIENT_TO_TARGET,
                handshakeResponse, 0, handshakeResponse.length);
        inspectTarget(changeUserExtractor, 2, new byte[]{0x00});

        ByteArrayOutputStream payload = new ByteArrayOutputStream();
        payload.write(MySQLCommandType.COM_CHANGE_USER.getCode());
        payload.writeBytes(cstring("second_user"));
        payload.write(20);
        payload.writeBytes(new byte[20]);
        payload.writeBytes(cstring("second_db"));
        byte[] changeUser = rawPacket(0, payload.toByteArray());

        assertThat(changeUserExtractor.inspect(TrafficDirection.CLIENT_TO_TARGET,
                changeUser, 0, changeUser.length)).isEmpty();

        assertThat(session.getAttribute("client.user")).contains("second_user");
        assertThat(session.getCurrentDatabase()).contains("second_db");
    }

    @Test
    void clearsObservedStateOnComResetConnection() {
        MySQLSession session = new MySQLSession("mysql-reset");
        MySQLDatabaseEventExtractor resetExtractor =
                new MySQLDatabaseEventExtractor("MySQL", "mysql-reset", true, session);

        sendQuery(resetExtractor);
        inspectTarget(resetExtractor, 1, okPayload(3, 0x0003));
        assertThat(session.getTransactionStatus()).isEqualTo(MySQLSession.TransactionStatus.IN_TRANSACTION);
        assertThat(session.getLastAffectedRows()).isEqualTo(3);

        byte[] reset = rawPacket(0, new byte[]{(byte) MySQLCommandType.COM_RESET_CONNECTION.getCode()});
        assertThat(resetExtractor.inspect(TrafficDirection.CLIENT_TO_TARGET,
                reset, 0, reset.length)).isEmpty();

        assertThat(session.getTransactionStatus()).isEqualTo(MySQLSession.TransactionStatus.IDLE);
        assertThat(session.isAutocommit()).isTrue();
        assertThat(session.getLastAffectedRows()).isZero();
        assertThat(session.getLastResultSetColumnCount()).isEqualTo(-1);
    }

    @Test
    void offersPacketBoundariesUntilTheSessionBecomesOpaque() {
        MySQLDatabaseEventExtractor boundaryExtractor =
                new MySQLDatabaseEventExtractor("MySQL", "mysql-bounder", false, null);

        var bounder = boundaryExtractor.messageBounder(TrafficDirection.CLIENT_TO_TARGET);
        assertThat(bounder).isNotNull();
        byte[] packet = rawPacket(0, new byte[]{0x01, 0x02, 0x03});
        assertThat(bounder.completeMessageEnds(packet, 0, packet.length)).containsExactly(packet.length);

        // A handshake response that opts into TLS turns the session into an opaque
        // tunnel, where there is no cleartext framing left to trust.
        byte[] sslResponse = rawPacket(1, capabilityPayload(
                MySQLCapability.CLIENT_PROTOCOL_41.getFlag() | MySQLCapability.CLIENT_SSL.getFlag()));
        boundaryExtractor.inspect(TrafficDirection.CLIENT_TO_TARGET, sslResponse, 0, sslResponse.length);

        // The bounder stays valid for the session and now reports that no boundary
        // can be trusted: an opaque tunnel has no cleartext framing left.
        assertThat(boundaryExtractor.messageBounder(TrafficDirection.TARGET_TO_CLIENT)
                .completeMessageEnds(packet, 0, packet.length)).isNull();
        assertThat(bounder.completeMessageEnds(packet, 0, packet.length)).isNull();
    }

    @Test
    void capturesResultSetColumnMetadataForTheMaskingPath() {
        MySQLSession session = new MySQLSession("mysql-columns");
        MySQLDatabaseEventExtractor columnsExtractor =
                new MySQLDatabaseEventExtractor("MySQL", "mysql-columns", true, session);

        byte[] query = packet(0, MySQLCommandType.COM_QUERY.getCode(), "select id, email from accounts");
        columnsExtractor.inspect(TrafficDirection.CLIENT_TO_TARGET, query, 0, query.length);
        inspectTarget(columnsExtractor, 1, new byte[]{0x02});
        inspectTarget(columnsExtractor, 2, columnDefinitionPayload("id", 0x08, 255));
        inspectTarget(columnsExtractor, 3, columnDefinitionPayload("email", 0x0F, 255));
        inspectTarget(columnsExtractor, 4, new byte[]{(byte) 0xFE, 0x00, 0x00, 0x02, 0x00});
        inspectTarget(columnsExtractor, 5, new byte[]{0x01, '1', 0x03, 'a', 'b', 'c'});

        assertThat(columnsExtractor.currentResultSetColumnCount()).isEqualTo(2);
        // A COM_QUERY result set carries text values; the masking path relies on it.
        assertThat(columnsExtractor.pendingCommand()).contains(MySQLCommandType.COM_QUERY);
        assertThat(columnsExtractor.currentResultSetColumns())
                .extracting(com.whosly.gateway.masking.ColumnMetadata::format)
                .containsOnly(com.whosly.gateway.masking.ColumnMetadata.ValueFormat.TEXT);
        assertThat(columnsExtractor.currentResultSetColumns())
                .extracting(com.whosly.gateway.masking.ColumnMetadata::name,
                        com.whosly.gateway.masking.ColumnMetadata::category)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(
                                "id", com.whosly.gateway.masking.ColumnMetadata.Category.NUMERIC),
                        org.assertj.core.groups.Tuple.tuple(
                                "email", com.whosly.gateway.masking.ColumnMetadata.Category.TEXT));
        // The row packet is what a row masker would rewrite.
        assertThat(columnsExtractor.lastResponsePacketWasResultSetRow()).isTrue();

        // Ending the response drops the metadata, so no later result set sees stale columns.
        inspectTarget(columnsExtractor, 6, new byte[]{(byte) 0xFE, 0x00, 0x00, 0x02, 0x00});
        assertThat(columnsExtractor.currentResultSetColumns()).isEmpty();
        assertThat(columnsExtractor.lastResponsePacketWasResultSetRow()).isFalse();
    }

    private static byte[] columnDefinitionPayload(String name, int type, int collation) {
        ByteArrayOutputStream payload = new ByteArrayOutputStream();
        writeLengthEncodedString(payload, "def");
        writeLengthEncodedString(payload, "shop");
        writeLengthEncodedString(payload, "accounts");
        writeLengthEncodedString(payload, "accounts");
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
        byte[] bytes = value.getBytes(StandardCharsets.US_ASCII);
        payload.write(bytes.length);
        payload.writeBytes(bytes);
    }

    private static byte[] handshakeResponse(long capabilities, String username, byte[] authResponse,
                                            String database, String authPlugin) {
        ByteArrayOutputStream payload = new ByteArrayOutputStream();
        for (int index = 0; index < 4; index++) {
            payload.write((int) ((capabilities >> (index * 8)) & 0xFF));
        }
        payload.writeBytes(new byte[]{0, 0, 0, 1});
        payload.write(0x21);
        payload.writeBytes(new byte[23]);
        payload.writeBytes(cstring(username));
        payload.write(authResponse.length & 0xFF);
        payload.writeBytes(authResponse);
        if (database != null) {
            payload.writeBytes(cstring(database));
        }
        if (authPlugin != null) {
            payload.writeBytes(cstring(authPlugin));
        }
        return rawPacket(1, payload.toByteArray());
    }

    private static byte[] cstring(String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        byte[] cstring = new byte[bytes.length + 1];
        System.arraycopy(bytes, 0, cstring, 0, bytes.length);
        return cstring;
    }

    @Test
    void tracksTransactionStatusFromOkPacketStatusFlags() {
        MySQLSession session = new MySQLSession("mysql-transaction");
        MySQLDatabaseEventExtractor transactionExtractor =
                new MySQLDatabaseEventExtractor("MySQL", "mysql-transaction", true, session);

        sendQuery(transactionExtractor);
        byte[] beginOk = okPacket(1, 0, 0, 0x0003);
        transactionExtractor.inspect(TrafficDirection.TARGET_TO_CLIENT, beginOk, 0, beginOk.length);
        assertThat(session.getTransactionStatus()).isEqualTo(MySQLSession.TransactionStatus.IN_TRANSACTION);
        assertThat(session.isAutocommit()).isTrue();

        sendQuery(transactionExtractor);
        byte[] commitOk = okPacket(1, 1, 0, 0x0002);
        transactionExtractor.inspect(TrafficDirection.TARGET_TO_CLIENT, commitOk, 0, commitOk.length);
        assertThat(session.getTransactionStatus()).isEqualTo(MySQLSession.TransactionStatus.IDLE);
        assertThat(session.getLastAffectedRows()).isEqualTo(1);
    }

    @Test
    void recordsSqlStateFromErrorPacket() {
        MySQLSession session = new MySQLSession("mysql-error");
        MySQLDatabaseEventExtractor errorExtractor =
                new MySQLDatabaseEventExtractor("MySQL", "mysql-error", true, session);

        byte[] error = errPacket(1, 1064, "42000", "You have an error in your SQL syntax");
        errorExtractor.inspect(TrafficDirection.TARGET_TO_CLIENT, error, 0, error.length);

        assertThat(session.getLastSqlState()).contains("42000");
    }

    @Test
    void updatesTransactionStatusFromClassicResultSetEof() {
        MySQLSession session = new MySQLSession("mysql-resultset");
        MySQLDatabaseEventExtractor resultSetExtractor =
                new MySQLDatabaseEventExtractor("MySQL", "mysql-resultset", true, session);

        sendQuery(resultSetExtractor);
        inspectTarget(resultSetExtractor, 1, new byte[]{0x01});
        inspectTarget(resultSetExtractor, 2, new byte[]{0x03, 'd', 'e', 'f'});
        inspectTarget(resultSetExtractor, 3, new byte[]{(byte) 0xFE, 0x00, 0x00, 0x02, 0x00});
        inspectTarget(resultSetExtractor, 4, new byte[]{0x01, 'a'});
        inspectTarget(resultSetExtractor, 5, new byte[]{(byte) 0xFE, 0x00, 0x00, 0x03, 0x00});

        assertThat(session.getTransactionStatus()).isEqualTo(MySQLSession.TransactionStatus.IN_TRANSACTION);
    }

    @Test
    void updatesTransactionStatusFromOkAsEofWhenClientDeprecatesEof() {
        MySQLSession session = new MySQLSession("mysql-deprecate-eof");
        MySQLDatabaseEventExtractor deprecateExtractor =
                new MySQLDatabaseEventExtractor("MySQL", "mysql-deprecate-eof", false, session);

        byte[] handshakeResponse = rawPacket(1, capabilityPayload(
                MySQLCapability.CLIENT_PROTOCOL_41.getFlag()
                        | MySQLCapability.CLIENT_DEPRECATE_EOF.getFlag()));
        deprecateExtractor.inspect(TrafficDirection.CLIENT_TO_TARGET,
                handshakeResponse, 0, handshakeResponse.length);
        inspectTarget(deprecateExtractor, 2, new byte[]{0x00, 0x02});

        sendQuery(deprecateExtractor);
        inspectTarget(deprecateExtractor, 1, new byte[]{0x01});
        inspectTarget(deprecateExtractor, 2, new byte[]{0x03, 'd', 'e', 'f'});
        inspectTarget(deprecateExtractor, 3, new byte[]{0x01, 'a'});
        inspectTarget(deprecateExtractor, 4, new byte[]{(byte) 0xFE, 0x00, 0x00, 0x03, 0x00, 0x00, 0x00});

        assertThat(session.getTransactionStatus()).isEqualTo(MySQLSession.TransactionStatus.IN_TRANSACTION);
    }

    @Test
    void keepsObservingAfterServerMoreResultsStatusFlag() {
        MySQLSession session = new MySQLSession("mysql-more-results");
        MySQLDatabaseEventExtractor moreResultsExtractor =
                new MySQLDatabaseEventExtractor("MySQL", "mysql-more-results", true, session);

        sendQuery(moreResultsExtractor);
        inspectTarget(moreResultsExtractor, 1, okPayload(0, 0x0002 | 0x0008));
        inspectTarget(moreResultsExtractor, 1, new byte[]{0x01});
        inspectTarget(moreResultsExtractor, 2, new byte[]{0x03, 'd', 'e', 'f'});
        inspectTarget(moreResultsExtractor, 3, new byte[]{(byte) 0xFE, 0x00, 0x00, 0x02, 0x00});
        inspectTarget(moreResultsExtractor, 4, new byte[]{0x01, 'a'});
        inspectTarget(moreResultsExtractor, 5, new byte[]{(byte) 0xFE, 0x00, 0x00, 0x03, 0x00});

        assertThat(session.getTransactionStatus()).isEqualTo(MySQLSession.TransactionStatus.IN_TRANSACTION);
    }

    @Test
    void entersStreamingStateForLocalInfileAndIgnoresFileData() {
        MySQLSession session = new MySQLSession("mysql-infile");
        MySQLDatabaseEventExtractor infileExtractor =
                new MySQLDatabaseEventExtractor("MySQL", "mysql-infile", false, session);

        byte[] handshakeResponse = rawPacket(1, capabilityPayload(MySQLCapability.CLIENT_PROTOCOL_41.getFlag()));
        infileExtractor.inspect(TrafficDirection.CLIENT_TO_TARGET,
                handshakeResponse, 0, handshakeResponse.length);
        inspectTarget(infileExtractor, 2, new byte[]{0x00, 0x02});
        assertThat(session.getState()).isEqualTo(ProtocolConnectionState.READY);

        sendQuery(infileExtractor);

        // Server requests a LOAD DATA LOCAL INFILE upload.
        inspectTarget(infileExtractor, 1, new byte[]{(byte) 0xFB, '/', 't', 'm', 'p', '/', 'a', '.', 'c', 's', 'v'});
        assertThat(session.getState()).isEqualTo(ProtocolConnectionState.STREAMING);

        // File data whose first byte looks like COM_QUERY must not be audited.
        byte[] filePacket = rawPacket(2, new byte[]{0x03, 'd', 'a', 't', 'a'});
        assertThat(infileExtractor.inspect(TrafficDirection.CLIENT_TO_TARGET,
                filePacket, 0, filePacket.length)).isEmpty();
        assertThat(session.getState()).isEqualTo(ProtocolConnectionState.STREAMING);

        // The final OK closes the upload and marks the session ready again.
        inspectTarget(infileExtractor, 1, okPayload(1, 0x0002));
        assertThat(session.getState()).isEqualTo(ProtocolConnectionState.READY);
    }

    @Test
    void recordsSequenceAnomaliesWithoutDroppingTheSqlEvent() {
        MySQLDatabaseEventExtractor anomalyExtractor =
                new MySQLDatabaseEventExtractor("MySQL", "mysql-anomaly", true);

        byte[] oddSequencePacket = packet(7, MySQLCommandType.COM_QUERY.getCode(), "select 1");
        List<DatabaseTrafficEvent> events =
                anomalyExtractor.extract(oddSequencePacket, 0, oddSequencePacket.length);

        assertThat(events).singleElement()
                .extracting(DatabaseTrafficEvent::getStatement).isEqualTo("select 1");
        assertThat(anomalyExtractor.getProtocolAnomalyCount()).isEqualTo(1);
    }

    @Test
    void recordsFieldListColumnsWithoutMisreadingTheCatalogLength() {
        MySQLSession session = new MySQLSession("mysql-field-list");
        MySQLDatabaseEventExtractor fieldListExtractor = readyExtractor("mysql-field-list", session,
                MySQLCapability.CLIENT_PROTOCOL_41.getFlag());

        byte[] fieldList = packet(0, MySQLCommandType.COM_FIELD_LIST.getCode(), "t\u0000");
        assertThat(fieldListExtractor.inspect(TrafficDirection.CLIENT_TO_TARGET,
                fieldList, 0, fieldList.length)).isEmpty();

        // Definitions start with the catalog length (3, for "def"), not a column count.
        inspectTarget(fieldListExtractor, 1, new byte[]{0x03, 'd', 'e', 'f'});
        inspectTarget(fieldListExtractor, 2, new byte[]{0x03, 'd', 'e', 'f'});
        inspectTarget(fieldListExtractor, 3, new byte[]{(byte) 0xFE, 0x00, 0x00, 0x02, 0x00});

        assertThat(session.getLastResultSetColumnCount()).isEqualTo(2);
        assertThat(session.getLastResultSetRowCount()).isZero();
        assertThat(session.getState()).isEqualTo(ProtocolConnectionState.READY);
        assertThat(session.getObservationConfidence()).isEqualTo(ObservationConfidence.CONFIRMED);
    }

    @Test
    void completesComStatisticsWithoutInterpretingItsUnmarkedPayload() {
        MySQLSession session = new MySQLSession("mysql-statistics");
        MySQLDatabaseEventExtractor statisticsExtractor = readyExtractor("mysql-statistics", session,
                MySQLCapability.CLIENT_PROTOCOL_41.getFlag());

        byte[] statistics = rawPacket(0, new byte[]{(byte) MySQLCommandType.COM_STATISTICS.getCode()});
        statisticsExtractor.inspect(TrafficDirection.CLIENT_TO_TARGET,
                statistics, 0, statistics.length);
        assertThat(session.getState()).isEqualTo(ProtocolConnectionState.EXECUTING);

        // An unmarked string packet must not be read as a column count.
        inspectTarget(statisticsExtractor, 1,
                "Uptime: 42  Threads: 1".getBytes(StandardCharsets.UTF_8));

        assertThat(session.getState()).isEqualTo(ProtocolConnectionState.READY);
        assertThat(session.getLastResultSetColumnCount()).isEqualTo(-1);
        assertThat(session.getObservationConfidence()).isEqualTo(ObservationConfidence.CONFIRMED);
    }

    @Test
    void completesComSetOptionOnItsEofResponse() {
        MySQLSession session = new MySQLSession("mysql-set-option");
        MySQLDatabaseEventExtractor setOptionExtractor = readyExtractor("mysql-set-option", session,
                MySQLCapability.CLIENT_PROTOCOL_41.getFlag());

        byte[] setOption = rawPacket(0, new byte[]{
                (byte) MySQLCommandType.COM_SET_OPTION.getCode(), 0x00, 0x01});
        setOptionExtractor.inspect(TrafficDirection.CLIENT_TO_TARGET, setOption, 0, setOption.length);

        inspectTarget(setOptionExtractor, 1, new byte[]{(byte) 0xFE, 0x00, 0x00, 0x02, 0x00});

        assertThat(session.getState()).isEqualTo(ProtocolConnectionState.READY);
        assertThat(session.isAutocommit()).isTrue();
        assertThat(session.getObservationConfidence()).isEqualTo(ObservationConfidence.CONFIRMED);
    }

    @Test
    void keepsSessionReadyForCommandsThatDrawNoResponse() {
        MySQLSession session = new MySQLSession("mysql-no-response");
        MySQLDatabaseEventExtractor noResponseExtractor = readyExtractor("mysql-no-response", session,
                MySQLCapability.CLIENT_PROTOCOL_41.getFlag());

        byte[] sendLongData = rawPacket(0, new byte[]{
                (byte) MySQLCommandType.COM_STMT_SEND_LONG_DATA.getCode(), 0x01, 0x00, 0x00, 0x00});
        noResponseExtractor.inspect(TrafficDirection.CLIENT_TO_TARGET,
                sendLongData, 0, sendLongData.length);
        assertThat(session.getState()).isEqualTo(ProtocolConnectionState.READY);

        byte[] closeStatement = rawPacket(0, new byte[]{
                (byte) MySQLCommandType.COM_STMT_CLOSE.getCode(), 0x01, 0x00, 0x00, 0x00});
        noResponseExtractor.inspect(TrafficDirection.CLIENT_TO_TARGET,
                closeStatement, 0, closeStatement.length);

        assertThat(session.getState()).isEqualTo(ProtocolConnectionState.READY);
        assertThat(session.getObservationConfidence()).isEqualTo(ObservationConfidence.CONFIRMED);
    }

    @Test
    void consumesPrepareMetadataTailInsteadOfReadingItAsAResultSet() {
        MySQLSession session = new MySQLSession("mysql-prepare");
        MySQLDatabaseEventExtractor prepareExtractor = readyExtractor("mysql-prepare", session,
                MySQLCapability.CLIENT_PROTOCOL_41.getFlag());

        byte[] prepare = packet(0, MySQLCommandType.COM_STMT_PREPARE.getCode(), "select ? from t");
        prepareExtractor.inspect(TrafficDirection.CLIENT_TO_TARGET, prepare, 0, prepare.length);
        assertThat(session.getState()).isEqualTo(ProtocolConnectionState.EXECUTING);

        inspectTarget(prepareExtractor, 1, prepareOk(1, 1, 1));
        inspectTarget(prepareExtractor, 2, new byte[]{0x03, 'd', 'e', 'f'});
        inspectTarget(prepareExtractor, 3, new byte[]{(byte) 0xFE, 0x00, 0x00, 0x02, 0x00});
        inspectTarget(prepareExtractor, 4, new byte[]{0x03, 'd', 'e', 'f'});
        inspectTarget(prepareExtractor, 5, new byte[]{(byte) 0xFE, 0x00, 0x00, 0x02, 0x00});

        assertThat(session.getState()).isEqualTo(ProtocolConnectionState.READY);
        assertThat(prepareExtractor.getProtocolAnomalyCount()).isZero();
    }

    @Test
    void consumesPrepareMetadataTailWhenEofIsDeprecated() {
        MySQLSession session = new MySQLSession("mysql-prepare-deprecate");
        MySQLDatabaseEventExtractor prepareExtractor = readyExtractor("mysql-prepare-deprecate", session,
                MySQLCapability.CLIENT_PROTOCOL_41.getFlag()
                        | MySQLCapability.CLIENT_DEPRECATE_EOF.getFlag());

        byte[] prepare = packet(0, MySQLCommandType.COM_STMT_PREPARE.getCode(), "select ?");
        prepareExtractor.inspect(TrafficDirection.CLIENT_TO_TARGET, prepare, 0, prepare.length);

        inspectTarget(prepareExtractor, 1, prepareOk(7, 2, 1));
        inspectTarget(prepareExtractor, 2, new byte[]{0x03, 'd', 'e', 'f'});
        inspectTarget(prepareExtractor, 3, new byte[]{0x03, 'd', 'e', 'f'});
        inspectTarget(prepareExtractor, 4, new byte[]{0x03, 'd', 'e', 'f'});

        assertThat(session.getState()).isEqualTo(ProtocolConnectionState.READY);
        assertThat(prepareExtractor.getProtocolAnomalyCount()).isZero();
    }

    @Test
    void suspendsOnAnUnattributableResponseAndRecoversAtTheNextCommand() {
        MySQLSession session = new MySQLSession("mysql-resync");
        MySQLDatabaseEventExtractor resyncExtractor = readyExtractor("mysql-resync", session,
                MySQLCapability.CLIENT_PROTOCOL_41.getFlag());

        // A response packet with no command in flight cannot be attributed.
        inspectTarget(resyncExtractor, 1, new byte[]{0x02});
        assertThat(session.getObservationConfidence()).isEqualTo(ObservationConfidence.SUSPENDED);

        // The next command is the resync point.
        sendQuery(resyncExtractor);
        assertThat(session.getObservationConfidence()).isEqualTo(ObservationConfidence.CONFIRMED);

        inspectTarget(resyncExtractor, 1, new byte[]{0x01});
        inspectTarget(resyncExtractor, 2, new byte[]{0x03, 'd', 'e', 'f'});
        inspectTarget(resyncExtractor, 3, new byte[]{(byte) 0xFE, 0x00, 0x00, 0x02, 0x00});
        inspectTarget(resyncExtractor, 4, new byte[]{0x01, 'a'});
        inspectTarget(resyncExtractor, 5, new byte[]{(byte) 0xFE, 0x00, 0x00, 0x03, 0x00});

        assertThat(session.getLastResultSetColumnCount()).isEqualTo(1);
        assertThat(session.getLastResultSetRowCount()).isEqualTo(1);
        assertThat(session.getTransactionStatus()).isEqualTo(MySQLSession.TransactionStatus.IN_TRANSACTION);
    }

    @Test
    void degradesConfidenceWhenACommandSequenceIsUnexpected() {
        MySQLSession session = new MySQLSession("mysql-uncertain");
        MySQLDatabaseEventExtractor uncertainExtractor = readyExtractor("mysql-uncertain", session,
                MySQLCapability.CLIENT_PROTOCOL_41.getFlag());

        byte[] strayCommand = rawPacket(7, new byte[]{(byte) MySQLCommandType.COM_PING.getCode()});
        uncertainExtractor.inspect(TrafficDirection.CLIENT_TO_TARGET,
                strayCommand, 0, strayCommand.length);

        assertThat(session.getObservationConfidence()).isEqualTo(ObservationConfidence.UNCERTAIN);
    }

    @Test
    void tracksPreparedStatementsAcrossPrepareAndClose() {
        MySQLSession session = new MySQLSession("mysql-prepared");
        MySQLDatabaseEventExtractor preparedExtractor = readyExtractor("mysql-prepared", session,
                MySQLCapability.CLIENT_PROTOCOL_41.getFlag());

        byte[] prepare = packet(0, MySQLCommandType.COM_STMT_PREPARE.getCode(), "select 1");
        preparedExtractor.inspect(TrafficDirection.CLIENT_TO_TARGET, prepare, 0, prepare.length);
        inspectTarget(preparedExtractor, 1, prepareOk(1, 0, 0));

        assertThat(session.getOpenPreparedStatements()).isEqualTo(1);
        assertThat(session.getDirtiness().hasPreparedStatements()).isTrue();

        byte[] close = rawPacket(0, new byte[]{
                (byte) MySQLCommandType.COM_STMT_CLOSE.getCode(), 0x01, 0x00, 0x00, 0x00});
        preparedExtractor.inspect(TrafficDirection.CLIENT_TO_TARGET, close, 0, close.length);

        assertThat(session.getOpenPreparedStatements()).isZero();
        assertThat(session.getDirtiness().isClean()).isTrue();
    }

    @Test
    void clearsPreparedStatementsOnComResetConnection() {
        MySQLSession session = new MySQLSession("mysql-reset-prepared");
        MySQLDatabaseEventExtractor resetExtractor = readyExtractor("mysql-reset-prepared", session,
                MySQLCapability.CLIENT_PROTOCOL_41.getFlag());

        byte[] prepare = packet(0, MySQLCommandType.COM_STMT_PREPARE.getCode(), "select 1");
        resetExtractor.inspect(TrafficDirection.CLIENT_TO_TARGET, prepare, 0, prepare.length);
        inspectTarget(resetExtractor, 1, prepareOk(1, 0, 0));
        assertThat(session.getOpenPreparedStatements()).isEqualTo(1);

        byte[] reset = rawPacket(0, new byte[]{(byte) MySQLCommandType.COM_RESET_CONNECTION.getCode()});
        resetExtractor.inspect(TrafficDirection.CLIENT_TO_TARGET, reset, 0, reset.length);

        assertThat(session.getOpenPreparedStatements()).isZero();
        assertThat(session.getDirtiness().isClean()).isTrue();
    }

    /**
     * Builds a session whose handshake and authentication already completed, so
     * command-phase observation can be exercised directly.
     */
    private static MySQLDatabaseEventExtractor readyExtractor(String sessionName, MySQLSession session,
                                                              long capabilities) {
        MySQLDatabaseEventExtractor readyExtractor =
                new MySQLDatabaseEventExtractor("MySQL", sessionName, false, session);
        byte[] handshakeResponse = rawPacket(1, capabilityPayload(capabilities));
        readyExtractor.inspect(TrafficDirection.CLIENT_TO_TARGET,
                handshakeResponse, 0, handshakeResponse.length);
        inspectTarget(readyExtractor, 2, new byte[]{0x00});
        return readyExtractor;
    }

    /**
     * Builds a COM_STMT_PREPARE prepare-ok header: status, statement id, then the
     * column and parameter counts the metadata tail must match.
     */
    private static byte[] prepareOk(int statementId, int columnCount, int parameterCount) {
        ByteArrayOutputStream payload = new ByteArrayOutputStream();
        payload.write(0x00);
        for (int index = 0; index < 4; index++) {
            payload.write((statementId >> (index * 8)) & 0xFF);
        }
        payload.write(columnCount & 0xFF);
        payload.write((columnCount >> 8) & 0xFF);
        payload.write(parameterCount & 0xFF);
        payload.write((parameterCount >> 8) & 0xFF);
        payload.write(0x00);
        payload.write(0x00);
        payload.write(0x00);
        return payload.toByteArray();
    }

    /**
     * Sends one COM_QUERY command so the response that follows has a declared
     * shape. A real session always sends a command before a response, and the
     * observer refuses to guess now (rule 3.6/2.10).
     */
    private static void sendQuery(MySQLDatabaseEventExtractor extractor) {
        byte[] query = packet(0, MySQLCommandType.COM_QUERY.getCode(), "select 1");
        extractor.inspect(TrafficDirection.CLIENT_TO_TARGET, query, 0, query.length);
    }

    private static void inspectTarget(MySQLDatabaseEventExtractor extractor, int sequenceId, byte[] payload) {
        byte[] packet = rawPacket(sequenceId, payload);
        extractor.inspect(TrafficDirection.TARGET_TO_CLIENT, packet, 0, packet.length);
    }

    private static byte[] okPayload(int affectedRows, int statusFlags) {
        ByteArrayOutputStream payload = new ByteArrayOutputStream();
        payload.write(0x00);
        payload.write(affectedRows);
        payload.write(0x00);
        payload.write(statusFlags & 0xFF);
        payload.write((statusFlags >> 8) & 0xFF);
        payload.write(0);
        payload.write(0);
        return payload.toByteArray();
    }

    private static byte[] okPacket(int sequenceId, long affectedRows, long lastInsertId, int statusFlags) {
        ByteArrayOutputStream payload = new ByteArrayOutputStream();
        payload.write(0x00);
        payload.write((int) affectedRows);
        payload.write((int) lastInsertId);
        payload.write(statusFlags & 0xFF);
        payload.write((statusFlags >> 8) & 0xFF);
        payload.write(0);
        payload.write(0);
        return rawPacket(sequenceId, payload.toByteArray());
    }

    private static byte[] errPacket(int sequenceId, int errorCode, String sqlState, String message) {
        ByteArrayOutputStream payload = new ByteArrayOutputStream();
        payload.write(0xFF);
        payload.write(errorCode & 0xFF);
        payload.write((errorCode >> 8) & 0xFF);
        payload.write('#');
        payload.writeBytes(sqlState.getBytes(StandardCharsets.US_ASCII));
        payload.writeBytes(message.getBytes(StandardCharsets.UTF_8));
        return rawPacket(sequenceId, payload.toByteArray());
    }

    private static byte[] framedPayload(byte[] source, int offset, int payloadLength, int sequenceId) {
        byte[] packet = new byte[MySQLFrameCodec.HEADER_LENGTH + payloadLength];
        packet[0] = (byte) (payloadLength & 0xFF);
        packet[1] = (byte) ((payloadLength >> 8) & 0xFF);
        packet[2] = (byte) ((payloadLength >> 16) & 0xFF);
        packet[3] = (byte) (sequenceId & 0xFF);
        System.arraycopy(source, offset, packet, MySQLFrameCodec.HEADER_LENGTH, payloadLength);
        return packet;
    }

    private static byte[] packet(int sequenceId, int command, String sql) {
        byte[] sqlBytes = sql.getBytes(StandardCharsets.UTF_8);
        int payloadLength = sqlBytes.length + 1;
        byte[] packet = new byte[payloadLength + 4];
        packet[0] = (byte) (payloadLength & 0xFF);
        packet[1] = (byte) ((payloadLength >> 8) & 0xFF);
        packet[2] = (byte) ((payloadLength >> 16) & 0xFF);
        packet[3] = (byte) (sequenceId & 0xFF);
        packet[4] = (byte) command;
        System.arraycopy(sqlBytes, 0, packet, 5, sqlBytes.length);
        return packet;
    }

    private static byte[] rawPacket(int sequenceId, byte[] payload) {
        byte[] packet = new byte[payload.length + 4];
        packet[0] = (byte) (payload.length & 0xFF);
        packet[1] = (byte) ((payload.length >> 8) & 0xFF);
        packet[2] = (byte) ((payload.length >> 16) & 0xFF);
        packet[3] = (byte) (sequenceId & 0xFF);
        System.arraycopy(payload, 0, packet, 4, payload.length);
        return packet;
    }

    private static byte[] capabilityPayload(long capabilityFlags) {
        byte[] payload = new byte[32];
        payload[0] = (byte) (capabilityFlags & 0xFF);
        payload[1] = (byte) ((capabilityFlags >> 8) & 0xFF);
        payload[2] = (byte) ((capabilityFlags >> 16) & 0xFF);
        payload[3] = (byte) ((capabilityFlags >> 24) & 0xFF);
        return payload;
    }
}
