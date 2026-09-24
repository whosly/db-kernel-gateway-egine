import { apiDelete, apiGet, apiPost, apiPut } from './client'
import type {
  ActionResult,
  CatalogEntry,
  CreateInstancePayload,
  GatewayInstance,
  InstancesResponse,
  MaskingRule,
  MaskingRulePayload,
  MaskingRulesResponse,
  OverviewResponse,
} from './types'

export function getOverview() {
  return apiGet<OverviewResponse>('/overview')
}

export function listInstances() {
  return apiGet<InstancesResponse>('/instances')
}

export function getInstance(id: string) {
  return apiGet<GatewayInstance>(`/instances/${encodeURIComponent(id)}`)
}

export function getInstanceMetrics(id: string) {
  return apiGet<{
    id: string
    metrics: Record<string, number>
    pool?: import('./types').PoolStats
    activeConnections?: number | null
  }>(`/instances/${encodeURIComponent(id)}/metrics`)
}

export function startInstance(id: string) {
  return apiPost<ActionResult>(`/instances/${encodeURIComponent(id)}/start`)
}

export function stopInstance(id: string) {
  return apiPost<ActionResult>(`/instances/${encodeURIComponent(id)}/stop`)
}

export function createInstance(payload: CreateInstancePayload) {
  return apiPost<GatewayInstance>('/instances', payload)
}

export function deleteInstance(id: string) {
  return apiDelete<ActionResult>(`/instances/${encodeURIComponent(id)}`)
}

export function listSupportedDatabases() {
  return apiGet<{ databases: CatalogEntry[] }>('/supported-databases')
}

export function getConfigSummary() {
  return apiGet<Record<string, unknown>>('/config/summary')
}

export function getHealth() {
  return apiGet<Record<string, unknown>>('/health')
}

export function listMaskingRules(instanceId: string) {
  return apiGet<MaskingRulesResponse>(
    `/instances/${encodeURIComponent(instanceId)}/masking-rules`,
  )
}

export function createMaskingRule(instanceId: string, payload: MaskingRulePayload) {
  return apiPost<MaskingRule>(
    `/instances/${encodeURIComponent(instanceId)}/masking-rules`,
    payload,
  )
}

export function updateMaskingRule(
  instanceId: string,
  ruleId: string,
  payload: MaskingRulePayload,
) {
  return apiPut<MaskingRule>(
    `/instances/${encodeURIComponent(instanceId)}/masking-rules/${encodeURIComponent(ruleId)}`,
    payload,
  )
}

export function deleteMaskingRule(instanceId: string, ruleId: string) {
  return apiDelete<ActionResult>(
    `/instances/${encodeURIComponent(instanceId)}/masking-rules/${encodeURIComponent(ruleId)}`,
  )
}

export function getMaskingKeyStatus() {
  return apiGet<import('./types').MaskingKeyStatus>('/security/masking-key')
}

export function putMaskingKey(payload: { keyId?: string; keyBase64: string }) {
  return apiPut<import('./types').MaskingKeyStatus>('/security/masking-key', payload)
}

export function deleteMaskingKey() {
  return apiDelete<import('./types').MaskingKeyStatus>('/security/masking-key')
}

export function getSchemaColumns(instanceId: string, table?: string) {
  const q = table ? `?table=${encodeURIComponent(table)}` : ''
  return apiGet<import('./types').SchemaColumnsResponse>(
    `/instances/${encodeURIComponent(instanceId)}/schema/columns${q}`,
  )
}

export function listAudit(limit = 50, action?: string) {
  const q = new URLSearchParams({ limit: String(limit) })
  if (action) q.set('action', action)
  return apiGet<{ entries: import('./types').AuditEntry[]; count: number; actionFilter?: string }>(
    `/audit?${q.toString()}`,
  )
}

export function getAuditStatus() {
  return apiGet<import('./types').AuditStatus>('/audit/status')
}

export function getMetricsHistory(instanceId?: string, limit = 120) {
  const q = new URLSearchParams({ limit: String(limit) })
  if (instanceId) q.set('instanceId', instanceId)
  return apiGet<import('./types').MetricsHistoryResponse>(`/metrics/history?${q.toString()}`)
}

export function getRiskPolicy() {
  return apiGet<import('./types').RiskPolicy>('/risk-policy')
}

export function putRiskPolicy(payload: {
  enabled?: boolean
  deniedOperations?: string[]
  deniedStatementKeywords?: string[]
}) {
  return apiPut<import('./types').RiskPolicy>('/risk-policy', payload)
}


export function listSessions(instanceId: string) {
  return apiGet<{ instanceId: string; sessions: import('./types').SessionRow[]; count: number }>(
    `/instances/${encodeURIComponent(instanceId)}/sessions`,
  )
}

export function killSession(instanceId: string, connectionId: string) {
  return apiDelete<ActionResult>(
    `/instances/${encodeURIComponent(instanceId)}/sessions/${encodeURIComponent(connectionId)}`,
  )
}

export function healthCheck(instanceId: string) {
  return apiPost<import('./types').HealthCheckResult>(
    `/instances/${encodeURIComponent(instanceId)}/health-check`,
  )
}

export function listRecentStatements(instanceId: string, limit = 50) {
  return apiGet<{
    instanceId: string
    entries: import('./types').RecentStatement[]
    count: number
    note?: string
  }>(`/instances/${encodeURIComponent(instanceId)}/recent-statements?limit=${limit}`)
}

export async function downloadInstancesExport() {
  const data = await apiGet<unknown[]>('/instances/export')
  triggerDownload(data, `gateway-instances-${Date.now()}.json`)
}

export async function downloadConfigExport() {
  const data = await apiGet<Record<string, unknown>>('/config/export')
  triggerDownload(data, `gateway-config-${Date.now()}.json`)
}

function triggerDownload(data: unknown, filename: string) {
  const blob = new Blob([JSON.stringify(data, null, 2)], { type: 'application/json' })
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = filename
  a.click()
  URL.revokeObjectURL(url)
}
