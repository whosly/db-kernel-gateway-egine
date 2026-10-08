package com.whosly.gateway.console.persist;

import com.whosly.gateway.console.security.ConsoleSecretCipher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Control-plane secrets (e.g. console-overridden masking key). Values stored encrypted when
 * {@link ConsoleSecretCipher} has a master key.
 */
@Repository
public class ConsoleSecretsStore {

    public static final String KEY_MASKING = "masking.key";

    private static final Logger log = LoggerFactory.getLogger(ConsoleSecretsStore.class);

    private static final String DDL = """
            CREATE TABLE IF NOT EXISTS gateway_console_secret (
              secret_key   VARCHAR(128) PRIMARY KEY,
              secret_value VARCHAR(4096) NOT NULL,
              meta_json    VARCHAR(1024),
              updated_at   TIMESTAMP NOT NULL
            )
            """;

    private final JdbcTemplate jdbc;
    private final ConsoleSecretCipher cipher;

    public ConsoleSecretsStore(JdbcTemplate jdbc, ConsoleSecretCipher cipher) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.cipher = Objects.requireNonNull(cipher, "cipher");
        jdbc.execute(DDL);
        log.info("Console control-plane table gateway_console_secret ready");
    }

    public void put(String key, String plaintextValue, String metaJson) {
        Objects.requireNonNull(key, "key");
        String sealed = cipher.requireSeal(plaintextValue);
        Instant now = Instant.now();
        jdbc.update(
                """
                MERGE INTO gateway_console_secret (secret_key, secret_value, meta_json, updated_at)
                KEY (secret_key)
                VALUES (?, ?, ?, ?)
                """,
                key, sealed, metaJson, Timestamp.from(now));
    }

    public Optional<OpenedSecret> find(String key) {
        List<OpenedSecret> rows = jdbc.query(
                "SELECT secret_key, secret_value, meta_json, updated_at FROM gateway_console_secret WHERE secret_key = ?",
                (rs, n) -> {
                    String sealed = rs.getString("secret_value");
                    String plain = cipher.openFromStorage(sealed);
                    Timestamp ts = rs.getTimestamp("updated_at");
                    return new OpenedSecret(
                            rs.getString("secret_key"),
                            plain,
                            rs.getString("meta_json"),
                            ts != null ? ts.toInstant() : Instant.EPOCH);
                },
                key);
        return rows.stream().findFirst();
    }

    public boolean delete(String key) {
        return jdbc.update("DELETE FROM gateway_console_secret WHERE secret_key = ?", key) > 0;
    }

    public record OpenedSecret(String key, String plaintext, String metaJson, Instant updatedAt) {
    }
}
