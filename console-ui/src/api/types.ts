/** Mirrors console API contract v1 (CONSOLE_ARCHITECTURE §2.4). */

export type InstanceStatus = 'RUNNING' | 'STOPPED' | 'DISABLED' | 'UNBOUND' | 'UNSUPPORTED'

export type ProxyMode = 'GATEWAY' | 'TRANSPARENT' | 'UNSUPPORTED'

export interface CatalogEntry {
  id: string
  displayName: string
  enabled: boolean
  maturity: string
  defaultProxyPort: number
  defaultTargetPort: number
  notes?: string
  registered?: boolean
  creatable?: boolean
  consoleCreateAllowed?: boolean
  /** GATEWAY | TRANSPARENT | UNSUPPORTED */
  proxyMode?: ProxyMode | string
  /** 中文：网关代理 / 透明代理 / 不支持 */
  proxyModeLabel?: string
}

export interface GatewayInstance {
  id: string
  name: string
  dbType: string
  listenHost: string
  listenPort: number
  enabled: boolean
  status: InstanceStatus
  bound: boolean
  startable: boolean
  targetHost: string
  targetPort: number
  targetDatabase: string
  targetUsername: string
  passwordConfigured: boolean
  activeSessions?: number | null
  activeConnections?: number | null
  maxConnections?: number | null
  metrics: Record<string, number>
  message: string
  source: string
  /** GATEWAY | TRANSPARENT | UNSUPPORTED */
  proxyMode?: ProxyMode | string
  /** 中文：网关代理 / 透明代理 / 不支持 */
  proxyModeLabel?: string
}

export interface OverviewResponse {
  health: Record<string, unknown>
  instances: GatewayInstance[]
  databases: CatalogEntry[]
  metrics: Record<string, number>
  metricsScope: string
  legacyMetrics: Record<string, number>
  config: Record<string, unknown>
  byStatus: Record<string, number>
}

export interface InstancesResponse {
  items: GatewayInstance[]
  total: number
  byStatus?: Record<string, number>
}

export interface ActionResult {
  ok: boolean
  message?: string
  [key: string]: unknown
}

export interface CreateInstancePayload {
  id?: string
  name: string
  dbType: string
  listenHost?: string
  listenPort: number
  targetHost: string
  targetPort: number
  targetDatabase?: string
  targetUsername?: string
  targetPassword?: string
  enabled?: boolean
}

export interface UpdateInstancePayload {
  name?: string
  listenHost?: string
  listenPort?: number
  targetHost?: string
  targetPort?: number
  targetDatabase?: string
  targetUsername?: string
  targetPassword?: string
  enabled?: boolean
}

export interface CloneInstancePayload {
  id?: string
  name?: string
  listenPort?: number
  copyMaskingRules?: boolean
}

export interface ImportInstancesPayload {
  instances: Record<string, unknown>[]
  replace?: boolean
  skipExisting?: boolean
}

export interface BulkResult {
  action: string
  results: { id: string; ok: boolean; message?: string }[]
  okCount: number
  failCount: number
  ok: boolean
}

export interface SqlExecutePayload {
  sql: string
  maxRows?: number
  timeoutMs?: number
  /** Client-generated id so Cancel can target this in-flight execute. */
  executionId?: string
  /** Default false: stop on first error. */
  continueOnError?: boolean
}

export interface SqlStatementResult {
  index: number
  ok: boolean
  sql?: string
  columns?: string[]
  rows?: (string | number | boolean | null)[][]
  rowCount?: number
  truncated?: boolean
  updateCount?: number
  warnings?: string[]
  error?: string
  durationMs?: number
}

export interface SqlExecuteResult {
  ok: boolean
  columns: string[]
  rows: (string | number | boolean | null)[][]
  rowCount: number
  truncated: boolean
  durationMs: number
  message?: string
  warnings?: string[]
  note?: string
  updateCount?: number
  viaProxy?: boolean
  proxyHost?: string
  proxyPort?: number
  instanceId?: string
  dbType?: string
  executionId?: string
  statementCount?: number
  results?: SqlStatementResult[]
  stoppedAt?: number
  cancelled?: boolean
  cancelNote?: string
  cancelBestEffort?: boolean
  cancelDisclaimer?: string
}

export interface SqlCancelResult {
  ok: boolean
  executionId?: string
  instanceId?: string
  found?: boolean
  cancelRequested?: boolean
  message?: string
  cancelBestEffort?: boolean
  cancelDisclaimer?: string
}

export type MaskingStrategy = 'null' | 'fixed' | 'partial' | 'hash' | 'encrypt'

export interface MaskingRule {
  id: string
  instanceId: string
  name: string
  strategy: MaskingStrategy | string
  priority: number
  columnName?: string | null
  tableName?: string | null
  namePattern?: string | null
  fixedValue?: string | null
  keepPrefix?: number | null
  keepSuffix?: number | null
  hashHexLength?: number | null
  enabled: boolean
  createdAt?: string | null
  updatedAt?: string | null
}

export interface MaskingRulePayload {
  id?: string
  name: string
  strategy: MaskingStrategy | string
  priority?: number
  columnName?: string
  tableName?: string
  namePattern?: string
  fixedValue?: string
  keepPrefix?: number
  keepSuffix?: number
  hashHexLength?: number
  enabled?: boolean
}

export interface MaskingRulesResponse {
  instanceId?: string
  items: MaskingRule[]
  total: number
}

export interface MaskingKeyStatus {
  configured: boolean
  keyId: string | null
  activeKeyId?: string | null
  source: 'config' | 'console' | 'none' | string
  previousKeyIds?: string[]
  encryptRulesCanBind?: boolean
  encryptRuleCount?: number
  encryptRulesWithoutKey?: number
  warning?: string
  consoleMasterKeyConfigured?: boolean
  requireSecretEncryption?: boolean
  allowsPlaintextWrites?: boolean
  secretEncryption?: SecretEncryptionStatus
  ok?: boolean
  message?: string
  reload?: Record<string, unknown>
}

export interface MaskingKeyVerifyResult {
  ok: boolean
  keyId?: string
  message?: string
}

/** Control-plane password envelope status (no key material). */
export interface SecretEncryptionStatus {
  masterKeyConfigured: boolean
  requireSecretEncryption: boolean
  allowsPlaintextWrites: boolean
  storageMode: 'encrypted' | 'lab-plaintext' | 'require-encrypted-blocked' | string
  help?: string
}

export interface SchemaColumn {
  name: string
  table: string
  nullable: boolean
  typeName: string
}

export interface SchemaColumnsResponse {
  instanceId: string
  dbType: string
  table?: string | null
  columns: SchemaColumn[]
  count: number
}

export interface AuditEntry {
  id: string
  at: string | null
  action: string
  instanceId?: string | null
  detailJson?: string | null
  actor?: string | null
}


export interface SessionRow {
  connectionId: string
  protocolName?: string
  state?: string
  confidence?: string
  inTransaction?: boolean
  clientUser?: string | null
  clientDatabase?: string | null
  dirtiness?: Record<string, unknown>
  connectedAt?: string | null
  lastActivity?: string | null
}

export interface HealthCheckResult {
  ok: boolean
  latencyMs: number
  targetHost: string
  targetPort: number
  message?: string
  checkedAt?: string
  tcpOk?: boolean
  jdbcOk?: boolean
  instanceId?: string
}

export interface RecentStatement {
  instanceId?: string | null
  sessionId?: string
  protocolName?: string
  operation?: string
  eventType?: string
  statement?: string
  observedAt?: string | null
}

export interface PoolStats {
  enabled: boolean
  idleCount: number
  maxIdle: number
}

export interface AuditStatus {
  enabled: boolean
  destination?: string
  spoolDir?: string
  maskStatements?: boolean
  shipperRunning?: boolean
  recordsPendingHint?: number | null
  consoleAuditCount?: number
  help?: string
}

export interface TrafficAuditEntry {
  ts?: number | null
  observedAt?: string | null
  protocolName?: string | null
  sessionId?: string | null
  sequence?: number | null
  operation?: string | null
  statement?: string | null
  instanceId?: string | null
  source?: string | null
  segment?: string | null
}

export interface TrafficAuditBrowseResponse {
  auditEnabled: boolean
  destination?: string
  maskStatements?: boolean
  spoolDir?: string
  jdbcConfigured?: boolean
  source: string
  items: TrafficAuditEntry[]
  total: number
  limit?: number
  before?: number | null
  nextBefore?: number | null
  note?: string
  help?: string
}

export interface MetricsHistoryPoint {
  t: number
  connectionsAccepted?: number
  policyDenials?: number
  activeConnections?: number
  [key: string]: number | undefined
}

export interface MetricsHistoryResponse {
  intervalSeconds: number
  instanceId?: string | null
  scope: string
  points: MetricsHistoryPoint[]
  total: number
  capacity?: number
  note?: string
}

export interface RiskPolicy {
  enabled: boolean
  deniedOperations: string[]
  deniedStatementKeywords: string[]
  source: string
  updatedAt?: string | null
  yamlDefaults?: {
    deniedOperations: string[]
    deniedStatementKeywords: string[]
  }
  note?: string
  ok?: boolean
  message?: string
}

export interface SchemaCatalogResponse {
  instanceId: string
  dbType: string
  schemas: { name: string }[]
  tables: { schema: string; name: string; type?: string }[]
  schemaCount?: number
  tableCount?: number
  note?: string
}

export interface SqlHistoryEntry {
  id: string
  instanceId?: string | null
  sql: string
  ok: boolean
  durationMs?: number | null
  rowCount?: number | null
  createdAt?: string | null
  actor?: string | null
}

export interface SqlSnippet {
  id: string
  name: string
  sql: string
  createdAt?: string | null
  updatedAt?: string | null
}

export interface AuthModeResponse {
  mode: string
  formLogin?: boolean
  oidc?: boolean
  token?: boolean
  open?: boolean
  registrationId?: string
  ssoLoginUrl?: string
  oidcConfigured?: boolean
}

export interface AuthMeResponse {
  authenticated: boolean
  username?: string | null
  roles?: string[]
  mode?: string
}

export interface AlertThreshold {
  id: string
  name: string
  metricKey: string
  comparator: string
  thresholdValue: number
  windowSeconds?: number | null
  instanceId?: string | null
  scope?: string
  enabled: boolean
  severity: string
  lastFiredAt?: string | null
  lastValue?: number | null
  lastFiring?: boolean
  createdAt?: string | null
  updatedAt?: string | null
}

export interface AlertThresholdPayload {
  name?: string
  metricKey: string
  comparator: string
  thresholdValue: number
  windowSeconds?: number | null
  instanceId?: string | null
  enabled?: boolean
  severity?: string
}

export interface AlertThresholdsResponse {
  items: AlertThreshold[]
  total: number
  metricKeys?: string[]
  comparators?: string[]
  severities?: string[]
  note?: string
}

export interface ActiveAlert {
  thresholdId: string
  name: string
  metricKey: string
  comparator: string
  thresholdValue: number
  windowSeconds?: number | null
  instanceId?: string | null
  scope?: string
  severity: string
  value: number
  valueMode?: string
  firedAt?: string
  message?: string
}

export interface ActiveAlertsResponse {
  items: ActiveAlert[]
  total: number
  evaluatedAt?: string | null
  note?: string
}

