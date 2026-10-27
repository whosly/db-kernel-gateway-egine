-- 审计留痕最终 sink 的表结构（规则 §8.5）
--
-- 关键点只有一个：PRIMARY KEY (session_id, record_sequence)。
-- spool → 数据库的搬运是「至少一次」的：进程在「已写入库、还没推进检查点」之间崩溃时，
-- 这一批会被重新搬运。唯一键把重复写入变成完整性冲突，网关据此判定「已存在」，
-- 从而在库侧收敛成恰好一次。若去掉这个主键，重放会重复计数，审计报表将无法自证。
--
-- 不要把这个表建在被代理的那个数据库实例上：审计与被审计的数据混在一起，
-- 既污染业务库，也让代理自身的语义纠缠不清（规则 §8.5）。


-- ---------------------------------------------------------------------------
-- H2（实验室独立文件库；网关 destination=jdbc 时会 CREATE TABLE IF NOT EXISTS）
-- 推荐 URL：jdbc:h2:file:./data/gateway-audit-sink;MODE=PostgreSQL;AUTO_SERVER=FALSE;DB_CLOSE_DELAY=-1
-- 勿与管控台 spring.datasource（gateway-console）混用同一文件；更勿写入被代理业务库。
-- ---------------------------------------------------------------------------
-- CREATE TABLE IF NOT EXISTS gateway_audit_record (
--     session_id      VARCHAR(128) NOT NULL,
--     record_sequence BIGINT       NOT NULL,
--     payload         CLOB         NOT NULL,
--     recorded_at     TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
--     PRIMARY KEY (session_id, record_sequence)
-- );

-- ---------------------------------------------------------------------------
-- MySQL
-- ---------------------------------------------------------------------------
CREATE TABLE gateway_audit_record (
    session_id      VARCHAR(128) NOT NULL COMMENT '网关侧会话标识，也是排序分片键',
    record_sequence BIGINT       NOT NULL COMMENT '会话内单调序号，幂等键的一半',
    payload         TEXT         NOT NULL COMMENT '已脱敏的单行审计条目（ts/protocol/operation/statement）',
    recorded_at     TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (session_id, record_sequence)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- ---------------------------------------------------------------------------
-- PostgreSQL
-- ---------------------------------------------------------------------------
-- CREATE TABLE gateway_audit_record (
--     session_id      VARCHAR(128) NOT NULL,
--     record_sequence BIGINT       NOT NULL,
--     payload         TEXT         NOT NULL,
--     recorded_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
--     PRIMARY KEY (session_id, record_sequence)
-- );

-- ---------------------------------------------------------------------------
-- 排查用：确认没有断号（每个会话的序号必须连续，从 0 开始）
-- ---------------------------------------------------------------------------
-- SELECT session_id,
--        COUNT(*)                       AS rows_total,
--        MIN(record_sequence)           AS first_sequence,
--        MAX(record_sequence)           AS last_sequence,
--        MAX(record_sequence) + 1 = COUNT(*) AS gapless
--   FROM gateway_audit_record
--  GROUP BY session_id;
