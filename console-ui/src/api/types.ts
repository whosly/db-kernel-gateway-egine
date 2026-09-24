/** Mirrors console API contract v1 (CONSOLE_ARCHITECTURE §2.4). */

export type InstanceStatus = 'RUNNING' | 'STOPPED' | 'DISABLED' | 'UNBOUND' | 'UNSUPPORTED'

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
  instances: GatewayInstance[]
  count: number
  byStatus: Record<string, number>
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
