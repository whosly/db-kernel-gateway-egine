package com.whosly.gateway.console.persist;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Control-plane H2 store for instance ACL bindings ({@code gateway_instance_acl}).
 * See CONSOLE_ARCHITECTURE §24.
 */
@Repository
public class InstanceAclStore {

    private static final Logger log = LoggerFactory.getLogger(InstanceAclStore.class);

    private static final String DDL = """
            CREATE TABLE IF NOT EXISTS gateway_instance_acl (
              id               VARCHAR(128)  PRIMARY KEY,
              principal_type   VARCHAR(16)   NOT NULL,
              principal        VARCHAR(256)  NOT NULL,
              instance_id      VARCHAR(128)  NOT NULL,
              permissions      VARCHAR(1024) NOT NULL,
              updated_at       TIMESTAMP     NOT NULL
            )
            """;

    private static final String IDX_UNIQUE = """
            CREATE UNIQUE INDEX IF NOT EXISTS uq_instance_acl_principal
              ON gateway_instance_acl (principal_type, principal, instance_id)
            """;

    private static final String IDX_INSTANCE = """
            CREATE INDEX IF NOT EXISTS idx_instance_acl_instance
              ON gateway_instance_acl (instance_id)
            """;

    private static final RowMapper<InstanceAclRecord> ROW_MAPPER = InstanceAclStore::mapRow;

    private final JdbcTemplate jdbc;

    public InstanceAclStore(JdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        jdbc.execute(DDL);
        jdbc.execute(IDX_UNIQUE);
        jdbc.execute(IDX_INSTANCE);
        log.info("Console control-plane table gateway_instance_acl ready (H2)");
    }

    public List<InstanceAclRecord> findAll() {
        return jdbc.query(
                """
                SELECT id, principal_type, principal, instance_id, permissions, updated_at
                FROM gateway_instance_acl
                ORDER BY instance_id ASC, principal_type ASC, principal ASC
                """,
                ROW_MAPPER);
    }

    public List<InstanceAclRecord> findByInstanceId(String instanceId) {
        return jdbc.query(
                """
                SELECT id, principal_type, principal, instance_id, permissions, updated_at
                FROM gateway_instance_acl
                WHERE instance_id = ?
                ORDER BY principal_type ASC, principal ASC
                """,
                ROW_MAPPER, instanceId);
    }

    public List<InstanceAclRecord> findMatching(String username, List<String> roleNames) {
        // Fetch all and filter in Java — H2 lab tables stay small; keeps OR logic clear.
        List<InstanceAclRecord> all = findAll();
        if (all.isEmpty()) {
            return List.of();
        }
        String user = username != null ? username.trim() : "";
        java.util.Set<String> roles = new java.util.LinkedHashSet<>();
        if (roleNames != null) {
            for (String r : roleNames) {
                if (r != null && !r.isBlank()) {
                    roles.add(normalizeRolePrincipal(r));
                }
            }
        }
        return all.stream().filter(row -> matches(row, user, roles)).toList();
    }

    public Optional<InstanceAclRecord> findById(String id) {
        List<InstanceAclRecord> rows = jdbc.query(
                """
                SELECT id, principal_type, principal, instance_id, permissions, updated_at
                FROM gateway_instance_acl
                WHERE id = ?
                """,
                ROW_MAPPER, id);
        return rows.stream().findFirst();
    }

    public Optional<InstanceAclRecord> findByPrincipalAndInstance(
            String principalType, String principal, String instanceId) {
        List<InstanceAclRecord> rows = jdbc.query(
                """
                SELECT id, principal_type, principal, instance_id, permissions, updated_at
                FROM gateway_instance_acl
                WHERE principal_type = ? AND principal = ? AND instance_id = ?
                """,
                ROW_MAPPER,
                normalizeType(principalType),
                normalizePrincipal(principalType, principal),
                instanceId);
        return rows.stream().findFirst();
    }

    public InstanceAclRecord upsert(String principalType, String principal,
                                    String instanceId, String permissionsCsv) {
        String type = normalizeType(principalType);
        String princ = normalizePrincipal(type, principal);
        String perms = permissionsCsv != null ? permissionsCsv.trim() : "";
        Instant now = Instant.now();
        Optional<InstanceAclRecord> existing = findByPrincipalAndInstance(type, princ, instanceId);
        if (existing.isPresent()) {
            String id = existing.get().id();
            jdbc.update(
                    """
                    UPDATE gateway_instance_acl
                    SET permissions = ?, updated_at = ?
                    WHERE id = ?
                    """,
                    perms, Timestamp.from(now), id);
            return findById(id).orElseThrow();
        }
        String id = UUID.randomUUID().toString().replace("-", "");
        jdbc.update(
                """
                INSERT INTO gateway_instance_acl
                  (id, principal_type, principal, instance_id, permissions, updated_at)
                VALUES (?, ?, ?, ?, ?, ?)
                """,
                id, type, princ, instanceId, perms, Timestamp.from(now));
        return findById(id).orElseThrow();
    }

    /** Replace all bindings for one instance. */
    public List<InstanceAclRecord> replaceForInstance(String instanceId,
                                                      List<AclWriteItem> items) {
        Objects.requireNonNull(instanceId, "instanceId");
        jdbc.update("DELETE FROM gateway_instance_acl WHERE instance_id = ?", instanceId);
        if (items != null) {
            for (AclWriteItem item : items) {
                if (item == null) {
                    continue;
                }
                upsert(item.principalType(), item.principal(), instanceId, item.permissionsCsv());
            }
        }
        return findByInstanceId(instanceId);
    }

    public boolean delete(String id) {
        return jdbc.update("DELETE FROM gateway_instance_acl WHERE id = ?", id) > 0;
    }

    public int deleteByInstanceId(String instanceId) {
        return jdbc.update("DELETE FROM gateway_instance_acl WHERE instance_id = ?", instanceId);
    }

    public boolean hasAnyMatching(String username, List<String> roleNames) {
        return !findMatching(username, roleNames).isEmpty();
    }

    private static boolean matches(InstanceAclRecord row, String user, java.util.Set<String> roles) {
        if ("user".equals(row.principalType())) {
            return !user.isEmpty() && user.equals(row.principal());
        }
        if ("role".equals(row.principalType())) {
            return roles.contains(row.principal());
        }
        return false;
    }

    public static String normalizeType(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("principalType 须为 user 或 role");
        }
        String t = raw.trim().toLowerCase(Locale.ROOT);
        if (!"user".equals(t) && !"role".equals(t)) {
            throw new IllegalArgumentException("principalType 须为 user 或 role");
        }
        return t;
    }

    public static String normalizePrincipal(String type, String principal) {
        if (principal == null || principal.isBlank()) {
            throw new IllegalArgumentException("principal 不能为空");
        }
        String p = principal.trim();
        if ("role".equals(normalizeType(type))) {
            return normalizeRolePrincipal(p);
        }
        return p;
    }

    public static String normalizeRolePrincipal(String raw) {
        String r = raw.trim();
        if (r.regionMatches(true, 0, "ROLE_", 0, 5)) {
            r = r.substring(5);
        }
        return com.whosly.gateway.console.security.ConsoleRoles.normalize(r);
    }

    private static InstanceAclRecord mapRow(ResultSet rs, int rowNum) throws SQLException {
        return new InstanceAclRecord(
                rs.getString("id"),
                rs.getString("principal_type"),
                rs.getString("principal"),
                rs.getString("instance_id"),
                rs.getString("permissions"),
                toInstant(rs.getTimestamp("updated_at")));
    }

    private static Instant toInstant(Timestamp ts) {
        return ts != null ? ts.toInstant() : null;
    }

    public record InstanceAclRecord(
            String id,
            String principalType,
            String principal,
            String instanceId,
            String permissions,
            Instant updatedAt
    ) {
    }

    public record AclWriteItem(String principalType, String principal, String permissionsCsv) {
    }
}
