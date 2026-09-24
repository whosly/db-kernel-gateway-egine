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
  return apiGet<{ id: string; metrics: Record<string, number> }>(
    `/instances/${encodeURIComponent(id)}/metrics`,
  )
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
