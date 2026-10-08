import { inject, ref, type Ref } from 'vue'

/** Stable permission strings — mirror backend ConsolePermission. */
export const Perm = {
  INSTANCES_READ: 'instances:read',
  INSTANCES_WRITE: 'instances:write',
  INSTANCES_START_STOP: 'instances:start_stop',
  INSTANCES_DELETE: 'instances:delete',
  SQL_EXECUTE: 'sql:execute',
  SESSIONS_KILL: 'sessions:kill',
  MASKING_WRITE: 'masking:write',
  SECURITY_KEYS: 'security:keys',
  AUDIT_READ: 'audit:read',
  ALERTS_READ: 'alerts:read',
  ALERTS_WRITE: 'alerts:write',
  RISK_WRITE: 'risk:write',
  METRICS_READ: 'metrics:read',
  SCHEMA_READ: 'schema:read',
  CONFIG_READ: 'config:read',
} as const

export type Permission = (typeof Perm)[keyof typeof Perm]

export function usePermissions() {
  const permissions = inject<Ref<string[]>>('permissions', ref([]))
  const authMode = inject<Ref<string>>('authMode', ref('open'))

  function has(p: string): boolean {
    if (authMode.value === 'open') return true
    return (permissions.value || []).includes(p)
  }

  return { permissions, has, Perm }
}
