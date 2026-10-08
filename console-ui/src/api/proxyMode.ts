import type { ProxyMode } from './types'

/** Short Chinese hint under badges (create form / drawer). */
export function proxyModeHint(mode?: string | null): string {
  switch (mode) {
    case 'GATEWAY':
      return '协议网关（观测/脱敏等）'
    case 'TRANSPARENT':
      return '字节透明转发'
    case 'UNSUPPORTED':
      return '该类型尚无可用代理能力'
    default:
      return ''
  }
}

export function proxyModeBadgeClass(mode?: string | null): string {
  switch (mode) {
    case 'GATEWAY':
      return 'proxy-gateway'
    case 'TRANSPARENT':
      return 'proxy-transparent'
    case 'UNSUPPORTED':
      return 'proxy-unsupported'
    default:
      return ''
  }
}

export function asProxyMode(mode?: string | null): ProxyMode | undefined {
  if (mode === 'GATEWAY' || mode === 'TRANSPARENT' || mode === 'UNSUPPORTED') return mode
  return undefined
}
