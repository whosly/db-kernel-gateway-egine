# Database Wire Protocol Foundation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build protocol-layer foundations for MySQL and PostgreSQL gateway proxying while preserving extension points for future database protocols.

**Architecture:** Introduce protocol-neutral state/session/error abstractions in `adapter.protocol`, then move MySQL packet primitives and PostgreSQL message primitives behind focused codecs. Existing adapters can continue to run while new protocol components are added and tested in small increments.

**Tech Stack:** Java 17, Spring Boot 3.1.0, Maven, JUnit 5, AssertJ, Mockito.

## Global Constraints

- Follow `docs/rules/database-protocol-rules.md`.
- MySQL and PostgreSQL are first-phase protocols.
- Protocol frame/message codecs must validate length, endian, message type, sequence/message ordering, and legal state transition before mutating session state.
- Startup, capability negotiation, authentication, query execution, streaming, cancellation, and termination must remain separate protocol phases.
- Do not log plaintext credentials or authentication payloads.
- Do not implement unsupported future protocols by returning the MySQL or PostgreSQL adapter.
- Unit tests must not require local MySQL or PostgreSQL servers.
- New protocol behavior must be written test-first.

---

## File Structure

- `src/main/java/com/whosly/gateway/adapter/protocol/ProtocolConnectionState.java`: shared connection-state enum.
- `src/main/java/com/whosly/gateway/adapter/protocol/ProtocolMessage.java`: immutable protocol-neutral frame/message DTO.
- `src/main/java/com/whosly/gateway/adapter/protocol/ProtocolFrameCodec.java`: generic frame codec contract.
- `src/main/java/com/whosly/gateway/adapter/protocol/ProtocolSession.java`: per-client protocol session state.
- `src/main/java/com/whosly/gateway/adapter/protocol/ProtocolException.java`: sanitized protocol exception type.
- `src/main/java/com/whosly/gateway/adapter/protocol/ProtocolErrorMapper.java`: protocol-native error mapping contract.
- `src/main/java/com/whosly/gateway/adapter/mysql/MySQLFrameCodec.java`: MySQL packet read/write and primitive encoding.
- `src/main/java/com/whosly/gateway/adapter/mysql/MySQLCommandType.java`: MySQL command constants.
- `src/main/java/com/whosly/gateway/adapter/mysql/MySQLSession.java`: MySQL-specific session state.
- `src/main/java/com/whosly/gateway/adapter/postgresql/PostgreSQLMessageType.java`: PostgreSQL message constants.
- `src/main/java/com/whosly/gateway/adapter/postgresql/PostgreSQLFrameCodec.java`: PostgreSQL typed and startup message read/write.
- `src/main/java/com/whosly/gateway/adapter/postgresql/PostgreSQLSession.java`: PostgreSQL-specific session state.
- `src/test/java/com/whosly/gateway/adapter/protocol/ProtocolSessionTest.java`: shared session behavior tests.
- `src/test/java/com/whosly/gateway/adapter/mysql/MySQLFrameCodecTest.java`: MySQL packet and primitive tests.
- `src/test/java/com/whosly/gateway/adapter/postgresql/PostgreSQLFrameCodecTest.java`: PostgreSQL message tests.
- `src/test/java/com/whosly/gateway/config/GatewayConfigTest.java`: unsupported-protocol fail-fast tests.

---

### Task 1: Protocol-Neutral Session Foundation

**Files:**
- Create: `src/main/java/com/whosly/gateway/adapter/protocol/ProtocolConnectionState.java`
- Create: `src/main/java/com/whosly/gateway/adapter/protocol/ProtocolSession.java`
- Create: `src/main/java/com/whosly/gateway/adapter/protocol/ProtocolException.java`
- Test: `src/test/java/com/whosly/gateway/adapter/protocol/ProtocolSessionTest.java`

**Interfaces:**
- Produces: `ProtocolConnectionState`, `ProtocolSession`, `ProtocolException`.
- Consumes: none.

- [ ] **Step 1: Write failing tests for initial state and legal transitions**

```java
package com.whosly.gateway.adapter.protocol;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProtocolSessionTest {

    @Test
    void startsConnectedAndMovesThroughStartupFlow() {
        ProtocolSession session = new ProtocolSession("mysql", "client-1");

        assertThat(session.getProtocolName()).isEqualTo("mysql");
        assertThat(session.getConnectionId()).isEqualTo("client-1");
        assertThat(session.getState()).isEqualTo(ProtocolConnectionState.CONNECTED);

        session.transitionTo(ProtocolConnectionState.NEGOTIATING);
        session.transitionTo(ProtocolConnectionState.AUTHENTICATING);
        session.transitionTo(ProtocolConnectionState.READY);

        assertThat(session.getState()).isEqualTo(ProtocolConnectionState.READY);
    }

    @Test
    void rejectsIllegalTransitionFromConnectedToExecuting() {
        ProtocolSession session = new ProtocolSession("postgresql", "client-2");

        assertThatThrownBy(() -> session.transitionTo(ProtocolConnectionState.EXECUTING))
            .isInstanceOf(ProtocolException.class)
            .hasMessageContaining("Illegal protocol state transition");
    }

    @Test
    void storesSessionAttributesWithoutExposingMutableState() {
        ProtocolSession session = new ProtocolSession("postgresql", "client-3");

        session.putAttribute("database", "demo");

        assertThat(session.getAttribute("database")).contains("demo");
        assertThat(session.attributes()).containsEntry("database", "demo");
        assertThatThrownBy(() -> session.attributes().put("user", "root"))
            .isInstanceOf(UnsupportedOperationException.class);
    }
}
```

- [ ] **Step 2: Run the failing test**

Run: `mvn -Dtest=ProtocolSessionTest test`

Expected: compilation fails because `ProtocolSession`, `ProtocolConnectionState`, and `ProtocolException` do not exist.

- [ ] **Step 3: Implement the minimal shared protocol classes**

Create `ProtocolConnectionState.java`:

```java
package com.whosly.gateway.adapter.protocol;

public enum ProtocolConnectionState {
    CONNECTED,
    NEGOTIATING,
    AUTHENTICATING,
    READY,
    EXECUTING,
    STREAMING,
    CLOSING,
    CLOSED
}
```

Create `ProtocolException.java`:

```java
package com.whosly.gateway.adapter.protocol;

public class ProtocolException extends RuntimeException {

    public ProtocolException(String message) {
        super(message);
    }

    public ProtocolException(String message, Throwable cause) {
        super(message, cause);
    }
}
```

Create `ProtocolSession.java`:

```java
package com.whosly.gateway.adapter.protocol;

import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public class ProtocolSession {

    private static final Map<ProtocolConnectionState, Set<ProtocolConnectionState>> ALLOWED_TRANSITIONS =
        new EnumMap<>(ProtocolConnectionState.class);

    static {
        ALLOWED_TRANSITIONS.put(ProtocolConnectionState.CONNECTED,
            EnumSet.of(ProtocolConnectionState.NEGOTIATING, ProtocolConnectionState.AUTHENTICATING,
                ProtocolConnectionState.CLOSING, ProtocolConnectionState.CLOSED));
        ALLOWED_TRANSITIONS.put(ProtocolConnectionState.NEGOTIATING,
            EnumSet.of(ProtocolConnectionState.AUTHENTICATING, ProtocolConnectionState.READY,
                ProtocolConnectionState.CLOSING, ProtocolConnectionState.CLOSED));
        ALLOWED_TRANSITIONS.put(ProtocolConnectionState.AUTHENTICATING,
            EnumSet.of(ProtocolConnectionState.READY, ProtocolConnectionState.CLOSING,
                ProtocolConnectionState.CLOSED));
        ALLOWED_TRANSITIONS.put(ProtocolConnectionState.READY,
            EnumSet.of(ProtocolConnectionState.EXECUTING, ProtocolConnectionState.STREAMING,
                ProtocolConnectionState.CLOSING, ProtocolConnectionState.CLOSED));
        ALLOWED_TRANSITIONS.put(ProtocolConnectionState.EXECUTING,
            EnumSet.of(ProtocolConnectionState.READY, ProtocolConnectionState.STREAMING,
                ProtocolConnectionState.CLOSING, ProtocolConnectionState.CLOSED));
        ALLOWED_TRANSITIONS.put(ProtocolConnectionState.STREAMING,
            EnumSet.of(ProtocolConnectionState.READY, ProtocolConnectionState.CLOSING,
                ProtocolConnectionState.CLOSED));
        ALLOWED_TRANSITIONS.put(ProtocolConnectionState.CLOSING,
            EnumSet.of(ProtocolConnectionState.CLOSED));
        ALLOWED_TRANSITIONS.put(ProtocolConnectionState.CLOSED,
            EnumSet.noneOf(ProtocolConnectionState.class));
    }

    private final String protocolName;
    private final String connectionId;
    private final Map<String, Object> attributes = new HashMap<>();
    private ProtocolConnectionState state = ProtocolConnectionState.CONNECTED;

    public ProtocolSession(String protocolName, String connectionId) {
        this.protocolName = protocolName;
        this.connectionId = connectionId;
    }

    public String getProtocolName() {
        return protocolName;
    }

    public String getConnectionId() {
        return connectionId;
    }

    public ProtocolConnectionState getState() {
        return state;
    }

    public void transitionTo(ProtocolConnectionState nextState) {
        Set<ProtocolConnectionState> allowed = ALLOWED_TRANSITIONS.getOrDefault(state, Set.of());
        if (!allowed.contains(nextState)) {
            throw new ProtocolException("Illegal protocol state transition: " + state + " -> " + nextState);
        }
        this.state = nextState;
    }

    public void putAttribute(String key, Object value) {
        attributes.put(key, value);
    }

    public Optional<Object> getAttribute(String key) {
        return Optional.ofNullable(attributes.get(key));
    }

    public Map<String, Object> attributes() {
        return Collections.unmodifiableMap(attributes);
    }
}
```

- [ ] **Step 4: Run the test**

Run: `mvn -Dtest=ProtocolSessionTest test`

Expected: pass.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/whosly/gateway/adapter/protocol/ProtocolConnectionState.java \
  src/main/java/com/whosly/gateway/adapter/protocol/ProtocolSession.java \
  src/main/java/com/whosly/gateway/adapter/protocol/ProtocolException.java \
  src/test/java/com/whosly/gateway/adapter/protocol/ProtocolSessionTest.java
git commit -m "feat: add protocol session state foundation"
```

---

### Task 2: Shared Protocol Message and Codec Contract

**Files:**
- Create: `src/main/java/com/whosly/gateway/adapter/protocol/ProtocolMessage.java`
- Create: `src/main/java/com/whosly/gateway/adapter/protocol/ProtocolFrameCodec.java`
- Test: `src/test/java/com/whosly/gateway/adapter/protocol/ProtocolMessageTest.java`

**Interfaces:**
- Consumes: `ProtocolException`.
- Produces: `ProtocolMessage`, `ProtocolFrameCodec`.

- [ ] **Step 1: Write failing tests for immutable message payloads**

```java
package com.whosly.gateway.adapter.protocol;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProtocolMessageTest {

    @Test
    void copiesPayloadOnCreateAndRead() {
        byte[] payload = new byte[] {1, 2, 3};
        ProtocolMessage message = ProtocolMessage.typed('Q', payload, 7);

        payload[0] = 9;
        byte[] returnedPayload = message.payload();
        returnedPayload[1] = 8;

        assertThat(message.type()).contains('Q');
        assertThat(message.sequence()).contains(7);
        assertThat(message.payload()).containsExactly(1, 2, 3);
    }

    @Test
    void supportsUntypedStartupMessages() {
        ProtocolMessage message = ProtocolMessage.untyped(new byte[] {0, 3, 0, 0});

        assertThat(message.type()).isEmpty();
        assertThat(message.sequence()).isEmpty();
        assertThat(message.payload()).containsExactly(0, 3, 0, 0);
    }

    @Test
    void rejectsNullPayload() {
        assertThatThrownBy(() -> ProtocolMessage.untyped(null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("payload");
    }
}
```

- [ ] **Step 2: Run the failing test**

Run: `mvn -Dtest=ProtocolMessageTest test`

Expected: compilation fails because `ProtocolMessage` does not exist.

- [ ] **Step 3: Implement message DTO and codec contract**

Create `ProtocolMessage.java`:

```java
package com.whosly.gateway.adapter.protocol;

import java.util.Arrays;
import java.util.Optional;
import java.util.OptionalInt;

public final class ProtocolMessage {

    private final Character type;
    private final byte[] payload;
    private final Integer sequence;

    private ProtocolMessage(Character type, byte[] payload, Integer sequence) {
        if (payload == null) {
            throw new IllegalArgumentException("payload must not be null");
        }
        this.type = type;
        this.payload = Arrays.copyOf(payload, payload.length);
        this.sequence = sequence;
    }

    public static ProtocolMessage typed(char type, byte[] payload) {
        return new ProtocolMessage(type, payload, null);
    }

    public static ProtocolMessage typed(char type, byte[] payload, int sequence) {
        return new ProtocolMessage(type, payload, sequence);
    }

    public static ProtocolMessage untyped(byte[] payload) {
        return new ProtocolMessage(null, payload, null);
    }

    public Optional<Character> type() {
        return Optional.ofNullable(type);
    }

    public byte[] payload() {
        return Arrays.copyOf(payload, payload.length);
    }

    public OptionalInt sequence() {
        return sequence == null ? OptionalInt.empty() : OptionalInt.of(sequence);
    }
}
```

Create `ProtocolFrameCodec.java`:

```java
package com.whosly.gateway.adapter.protocol;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

public interface ProtocolFrameCodec {

    ProtocolMessage read(InputStream inputStream) throws IOException;

    void write(ProtocolMessage message, OutputStream outputStream) throws IOException;
}
```

- [ ] **Step 4: Run the test**

Run: `mvn -Dtest=ProtocolMessageTest test`

Expected: pass.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/whosly/gateway/adapter/protocol/ProtocolMessage.java \
  src/main/java/com/whosly/gateway/adapter/protocol/ProtocolFrameCodec.java \
  src/test/java/com/whosly/gateway/adapter/protocol/ProtocolMessageTest.java
git commit -m "feat: add protocol message codec contract"
```

---

### Task 3: MySQL Frame Codec

**Files:**
- Create: `src/main/java/com/whosly/gateway/adapter/mysql/MySQLFrameCodec.java`
- Modify: `src/main/java/com/whosly/gateway/adapter/mysql/MySQLPacket.java`
- Test: `src/test/java/com/whosly/gateway/adapter/mysql/MySQLFrameCodecTest.java`

**Interfaces:**
- Consumes: `ProtocolFrameCodec`, `ProtocolMessage`, `ProtocolException`.
- Produces: MySQL packet codec with validated 3-byte little-endian length and sequence id.

- [ ] **Step 1: Write failing tests for MySQL packet codec**

```java
package com.whosly.gateway.adapter.mysql;

import com.whosly.gateway.adapter.protocol.ProtocolException;
import com.whosly.gateway.adapter.protocol.ProtocolMessage;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MySQLFrameCodecTest {

    private final MySQLFrameCodec codec = new MySQLFrameCodec();

    @Test
    void writesPayloadLengthLittleEndianAndSequence() throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        codec.write(ProtocolMessage.typed((char) 0x03, new byte[] {'S', 'E', 'L'}, 9), output);

        assertThat(output.toByteArray()).containsExactly(3, 0, 0, 9, 'S', 'E', 'L');
    }

    @Test
    void readsPacketIntoProtocolMessage() throws Exception {
        ByteArrayInputStream input = new ByteArrayInputStream(new byte[] {3, 0, 0, 2, 'a', 'b', 'c'});

        ProtocolMessage message = codec.read(input);

        assertThat(message.sequence()).hasValue(2);
        assertThat(message.payload()).containsExactly('a', 'b', 'c');
        assertThat(message.type()).isEmpty();
    }

    @Test
    void rejectsPayloadTooLargeForSinglePacket() {
        byte[] payload = new byte[0x1000000];

        assertThatThrownBy(() -> codec.write(ProtocolMessage.typed((char) 0x03, payload, 0), new ByteArrayOutputStream()))
            .isInstanceOf(ProtocolException.class)
            .hasMessageContaining("MySQL packet payload too large");
    }

    @Test
    void rejectsTruncatedPayload() {
        ByteArrayInputStream input = new ByteArrayInputStream(new byte[] {4, 0, 0, 1, 'a', 'b'});

        assertThatThrownBy(() -> codec.read(input))
            .isInstanceOf(EOFException.class);
    }
}
```

- [ ] **Step 2: Run the failing test**

Run: `mvn -Dtest=MySQLFrameCodecTest test`

Expected: compilation fails because `MySQLFrameCodec` does not exist.

- [ ] **Step 3: Implement `MySQLFrameCodec` and bridge existing `MySQLPacket`**

Create `MySQLFrameCodec.java`:

```java
package com.whosly.gateway.adapter.mysql;

import com.whosly.gateway.adapter.protocol.ProtocolException;
import com.whosly.gateway.adapter.protocol.ProtocolFrameCodec;
import com.whosly.gateway.adapter.protocol.ProtocolMessage;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

public class MySQLFrameCodec implements ProtocolFrameCodec {

    private static final int HEADER_LENGTH = 4;
    private static final int MAX_SINGLE_PACKET_PAYLOAD = 0xFFFFFF;

    @Override
    public ProtocolMessage read(InputStream inputStream) throws IOException {
        byte[] header = readFully(inputStream, HEADER_LENGTH);
        int payloadLength = (header[0] & 0xFF)
            | ((header[1] & 0xFF) << 8)
            | ((header[2] & 0xFF) << 16);
        int sequenceId = header[3] & 0xFF;
        byte[] payload = readFully(inputStream, payloadLength);
        return ProtocolMessage.typed((char) 0, payload, sequenceId);
    }

    @Override
    public void write(ProtocolMessage message, OutputStream outputStream) throws IOException {
        byte[] payload = message.payload();
        if (payload.length > MAX_SINGLE_PACKET_PAYLOAD) {
            throw new ProtocolException("MySQL packet payload too large for single packet: " + payload.length);
        }
        int sequenceId = message.sequence().orElse(0) & 0xFF;
        outputStream.write(payload.length & 0xFF);
        outputStream.write((payload.length >> 8) & 0xFF);
        outputStream.write((payload.length >> 16) & 0xFF);
        outputStream.write(sequenceId);
        outputStream.write(payload);
    }

    private static byte[] readFully(InputStream inputStream, int length) throws IOException {
        byte[] bytes = new byte[length];
        int offset = 0;
        while (offset < length) {
            int count = inputStream.read(bytes, offset, length - offset);
            if (count < 0) {
                throw new EOFException("Unexpected end of MySQL packet");
            }
            offset += count;
        }
        return bytes;
    }
}
```

Modify `MySQLPacket.java` so `createPacket` and `readPacket` delegate to `MySQLFrameCodec` while preserving the existing public API used by adapters.

- [ ] **Step 4: Run MySQL codec tests**

Run: `mvn -Dtest=MySQLFrameCodecTest,MySqlProtocolAdapterTest test`

Expected: pass.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/whosly/gateway/adapter/mysql/MySQLFrameCodec.java \
  src/main/java/com/whosly/gateway/adapter/mysql/MySQLPacket.java \
  src/test/java/com/whosly/gateway/adapter/mysql/MySQLFrameCodecTest.java
git commit -m "feat: add validated mysql frame codec"
```

---

### Task 4: PostgreSQL Frame Codec

**Files:**
- Create: `src/main/java/com/whosly/gateway/adapter/postgresql/PostgreSQLFrameCodec.java`
- Modify: `src/main/java/com/whosly/gateway/adapter/postgresql/PostgreSQLPacket.java`
- Test: `src/test/java/com/whosly/gateway/adapter/postgresql/PostgreSQLFrameCodecTest.java`

**Interfaces:**
- Consumes: `ProtocolFrameCodec`, `ProtocolMessage`, `ProtocolException`.
- Produces: PostgreSQL typed-message codec with big-endian length validation plus startup-message read helper.

- [ ] **Step 1: Write failing tests for typed and startup messages**

```java
package com.whosly.gateway.adapter.postgresql;

import com.whosly.gateway.adapter.protocol.ProtocolException;
import com.whosly.gateway.adapter.protocol.ProtocolMessage;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PostgreSQLFrameCodecTest {

    private final PostgreSQLFrameCodec codec = new PostgreSQLFrameCodec();

    @Test
    void writesTypedMessageWithBigEndianLength() throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        codec.write(ProtocolMessage.typed('Q', new byte[] {'S', 0}), output);

        assertThat(output.toByteArray()).containsExactly('Q', 0, 0, 0, 6, 'S', 0);
    }

    @Test
    void readsTypedMessage() throws Exception {
        ByteArrayInputStream input = new ByteArrayInputStream(new byte[] {'Q', 0, 0, 0, 6, 'S', 0});

        ProtocolMessage message = codec.read(input);

        assertThat(message.type()).contains('Q');
        assertThat(message.payload()).containsExactly('S', 0);
    }

    @Test
    void readsUntypedStartupMessage() throws Exception {
        ByteArrayInputStream input = new ByteArrayInputStream(new byte[] {0, 0, 0, 8, 4, (byte) 0xD2, 22, 47});

        ProtocolMessage message = codec.readStartupMessage(input);

        assertThat(message.type()).isEmpty();
        assertThat(message.payload()).containsExactly(4, (byte) 0xD2, 22, 47);
    }

    @Test
    void rejectsLengthSmallerThanFour() {
        ByteArrayInputStream input = new ByteArrayInputStream(new byte[] {'Q', 0, 0, 0, 3});

        assertThatThrownBy(() -> codec.read(input))
            .isInstanceOf(ProtocolException.class)
            .hasMessageContaining("PostgreSQL message length");
    }

    @Test
    void rejectsTruncatedPayload() {
        ByteArrayInputStream input = new ByteArrayInputStream(new byte[] {'Q', 0, 0, 0, 8, 'a'});

        assertThatThrownBy(() -> codec.read(input))
            .isInstanceOf(EOFException.class);
    }
}
```

- [ ] **Step 2: Run the failing test**

Run: `mvn -Dtest=PostgreSQLFrameCodecTest test`

Expected: compilation fails because `PostgreSQLFrameCodec` does not exist.

- [ ] **Step 3: Implement `PostgreSQLFrameCodec` and bridge packet helpers**

Create `PostgreSQLFrameCodec.java`:

```java
package com.whosly.gateway.adapter.postgresql;

import com.whosly.gateway.adapter.protocol.ProtocolException;
import com.whosly.gateway.adapter.protocol.ProtocolFrameCodec;
import com.whosly.gateway.adapter.protocol.ProtocolMessage;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

public class PostgreSQLFrameCodec implements ProtocolFrameCodec {

    @Override
    public ProtocolMessage read(InputStream inputStream) throws IOException {
        int type = inputStream.read();
        if (type < 0) {
            throw new EOFException("Unexpected end of PostgreSQL message type");
        }
        int length = readInt4(inputStream);
        validateLength(length);
        byte[] payload = readFully(inputStream, length - 4);
        return ProtocolMessage.typed((char) type, payload);
    }

    public ProtocolMessage readStartupMessage(InputStream inputStream) throws IOException {
        int length = readInt4(inputStream);
        validateLength(length);
        byte[] payload = readFully(inputStream, length - 4);
        return ProtocolMessage.untyped(payload);
    }

    @Override
    public void write(ProtocolMessage message, OutputStream outputStream) throws IOException {
        char type = message.type().orElseThrow(() -> new ProtocolException("PostgreSQL typed message requires type"));
        byte[] payload = message.payload();
        int length = payload.length + 4;
        outputStream.write((byte) type);
        writeInt4(outputStream, length);
        outputStream.write(payload);
    }

    private static int readInt4(InputStream inputStream) throws IOException {
        byte[] bytes = readFully(inputStream, 4);
        return ((bytes[0] & 0xFF) << 24)
            | ((bytes[1] & 0xFF) << 16)
            | ((bytes[2] & 0xFF) << 8)
            | (bytes[3] & 0xFF);
    }

    private static void writeInt4(OutputStream outputStream, int value) throws IOException {
        outputStream.write((value >> 24) & 0xFF);
        outputStream.write((value >> 16) & 0xFF);
        outputStream.write((value >> 8) & 0xFF);
        outputStream.write(value & 0xFF);
    }

    private static void validateLength(int length) {
        if (length < 4) {
            throw new ProtocolException("PostgreSQL message length must be at least 4: " + length);
        }
    }

    private static byte[] readFully(InputStream inputStream, int length) throws IOException {
        byte[] bytes = new byte[length];
        int offset = 0;
        while (offset < length) {
            int count = inputStream.read(bytes, offset, length - offset);
            if (count < 0) {
                throw new EOFException("Unexpected end of PostgreSQL message");
            }
            offset += count;
        }
        return bytes;
    }
}
```

Modify `PostgreSQLPacket.java` so packet creation uses `PostgreSQLFrameCodec` or equivalent shared helpers while preserving existing static methods.

- [ ] **Step 4: Run PostgreSQL codec tests**

Run: `mvn -Dtest=PostgreSQLFrameCodecTest test`

Expected: pass.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/whosly/gateway/adapter/postgresql/PostgreSQLFrameCodec.java \
  src/main/java/com/whosly/gateway/adapter/postgresql/PostgreSQLPacket.java \
  src/test/java/com/whosly/gateway/adapter/postgresql/PostgreSQLFrameCodecTest.java
git commit -m "feat: add validated postgresql frame codec"
```

---

### Task 5: Protocol-Specific Session State

**Files:**
- Create: `src/main/java/com/whosly/gateway/adapter/mysql/MySQLSession.java`
- Create: `src/main/java/com/whosly/gateway/adapter/postgresql/PostgreSQLSession.java`
- Test: `src/test/java/com/whosly/gateway/adapter/mysql/MySQLSessionTest.java`
- Test: `src/test/java/com/whosly/gateway/adapter/postgresql/PostgreSQLSessionTest.java`

**Interfaces:**
- Consumes: `ProtocolSession`, `ProtocolConnectionState`.
- Produces: session state containers for MySQL capability flags/sequence and PostgreSQL transaction status/prepared state.

- [ ] **Step 1: Write failing MySQL session tests**

```java
package com.whosly.gateway.adapter.mysql;

import com.whosly.gateway.adapter.protocol.ProtocolConnectionState;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MySQLSessionTest {

    @Test
    void tracksCapabilitiesDatabaseAndSequence() {
        MySQLSession session = new MySQLSession("mysql-1");

        session.setClientCapabilities(0x00000200L);
        session.setCurrentDatabase("demo");

        assertThat(session.getState()).isEqualTo(ProtocolConnectionState.CONNECTED);
        assertThat(session.getClientCapabilities()).isEqualTo(0x00000200L);
        assertThat(session.getCurrentDatabase()).contains("demo");
        assertThat(session.nextServerSequence()).isEqualTo(1);
        assertThat(session.nextServerSequence()).isEqualTo(2);

        session.resetSequence();

        assertThat(session.nextServerSequence()).isEqualTo(1);
    }
}
```

- [ ] **Step 2: Write failing PostgreSQL session tests**

```java
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
```

- [ ] **Step 3: Run failing tests**

Run: `mvn -Dtest=MySQLSessionTest,PostgreSQLSessionTest test`

Expected: compilation fails because session classes do not exist.

- [ ] **Step 4: Implement session classes**

Implement `MySQLSession` extending `ProtocolSession` with client capabilities, optional current database, and server sequence counter.

Implement `PostgreSQLSession` extending `ProtocolSession` with parameter map, transaction status enum with wire codes `I/T/E`, and session-local prepared statement map.

- [ ] **Step 5: Run tests**

Run: `mvn -Dtest=MySQLSessionTest,PostgreSQLSessionTest test`

Expected: pass.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/whosly/gateway/adapter/mysql/MySQLSession.java \
  src/main/java/com/whosly/gateway/adapter/postgresql/PostgreSQLSession.java \
  src/test/java/com/whosly/gateway/adapter/mysql/MySQLSessionTest.java \
  src/test/java/com/whosly/gateway/adapter/postgresql/PostgreSQLSessionTest.java
git commit -m "feat: add protocol-specific session state"
```

---

### Task 6: Fail Fast for Unsupported Protocol Configuration

**Files:**
- Modify: `src/main/java/com/whosly/gateway/config/GatewayConfig.java`
- Test: `src/test/java/com/whosly/gateway/config/GatewayConfigTest.java`

**Interfaces:**
- Consumes: existing `GatewayConfig.protocolAdapter()`.
- Produces: clear failure for unsupported protocol names instead of returning MySQL as a placeholder.

- [ ] **Step 1: Write failing config test**

```java
package com.whosly.gateway.config;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GatewayConfigTest {

    @Test
    void rejectsUnsupportedProtocolInsteadOfFallingBackToMysql() {
        GatewayConfig config = new GatewayConfig();
        ReflectionTestUtils.setField(config, "proxyDbType", "oracle");

        assertThatThrownBy(config::protocolAdapter)
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Unsupported gateway proxy database protocol");
    }
}
```

- [ ] **Step 2: Run the failing test**

Run: `mvn -Dtest=GatewayConfigTest test`

Expected: test fails because `oracle` currently falls back to MySQL.

- [ ] **Step 3: Implement fail-fast behavior**

Change `GatewayConfig.protocolAdapter()` default and `oracle` branch to:

```java
throw new IllegalArgumentException("Unsupported gateway proxy database protocol: " + proxyDbType);
```

Keep `mysql` and `postgresql` behavior unchanged.

- [ ] **Step 4: Run config test**

Run: `mvn -Dtest=GatewayConfigTest test`

Expected: pass.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/whosly/gateway/config/GatewayConfig.java \
  src/test/java/com/whosly/gateway/config/GatewayConfigTest.java
git commit -m "fix: fail fast for unsupported gateway protocols"
```

---

## Self-Review

- Spec coverage: the plan implements the shared state-machine foundation, MySQL frame validation, PostgreSQL frame validation, protocol-specific session state, and unsupported protocol fail-fast behavior required by the rules.
- Deliberate deferral: full MySQL handshake refactor, PostgreSQL startup/auth refactor, command handlers, result mappers, and error mappers are intentionally left for the next plan after the frame/session foundation is green.
- Placeholder scan: no task uses TBD/TODO language for required first changes. Task 5 describes implementation behavior rather than full code because the class is straightforward and fully pinned by tests.
- Type consistency: `ProtocolSession`, `ProtocolMessage`, `ProtocolFrameCodec`, `MySQLFrameCodec`, `PostgreSQLFrameCodec`, `MySQLSession`, and `PostgreSQLSession` names are consistent across tasks.
