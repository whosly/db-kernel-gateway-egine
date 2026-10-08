const BASE = '/console/api/v1'

export class ApiError extends Error {
  status: number
  body: unknown
  constructor(status: number, message: string, body?: unknown) {
    super(message)
    this.status = status
    this.body = body
  }
}

function authHeaders(): Record<string, string> {
  const token =
    (typeof localStorage !== 'undefined' && localStorage.getItem('consoleApiToken')) ||
    (typeof import.meta !== 'undefined' &&
      (import.meta as ImportMeta & { env?: { VITE_CONSOLE_API_TOKEN?: string } }).env
        ?.VITE_CONSOLE_API_TOKEN) ||
    ''
  if (!token) return {}
  return { Authorization: `Bearer ${token}` }
}

function csrfHeaders(): Record<string, string> {
  if (typeof document === 'undefined') return {}
  const match = document.cookie.match(/(?:^|; )XSRF-TOKEN=([^;]*)/)
  if (!match) return {}
  try {
    return { 'X-XSRF-TOKEN': decodeURIComponent(match[1]) }
  } catch {
    return { 'X-XSRF-TOKEN': match[1] }
  }
}

let onUnauthorized: (() => void) | null = null
export function setUnauthorizedHandler(handler: (() => void) | null) {
  onUnauthorized = handler
}

export async function apiGet<T>(path: string): Promise<T> {
  const res = await fetch(`${BASE}${path}`, {
    credentials: 'include',
    headers: { Accept: 'application/json', ...authHeaders(), ...csrfHeaders() },
  })
  return parse<T>(res)
}

export async function apiPost<T>(
  path: string,
  body?: unknown,
  init?: { signal?: AbortSignal },
): Promise<T> {
  const res = await fetch(`${BASE}${path}`, {
    method: 'POST',
    credentials: 'include',
    headers: {
      Accept: 'application/json',
      'Content-Type': 'application/json',
      ...authHeaders(),
      ...csrfHeaders(),
    },
    body: body === undefined ? undefined : JSON.stringify(body),
    signal: init?.signal,
  })
  return parse<T>(res)
}

export async function apiPut<T>(path: string, body?: unknown): Promise<T> {
  const res = await fetch(`${BASE}${path}`, {
    method: 'PUT',
    credentials: 'include',
    headers: {
      Accept: 'application/json',
      'Content-Type': 'application/json',
      ...authHeaders(),
      ...csrfHeaders(),
    },
    body: body === undefined ? undefined : JSON.stringify(body),
  })
  return parse<T>(res)
}

export async function apiDelete<T>(path: string): Promise<T> {
  const res = await fetch(`${BASE}${path}`, {
    method: 'DELETE',
    credentials: 'include',
    headers: { Accept: 'application/json', ...authHeaders(), ...csrfHeaders() },
  })
  return parse<T>(res)
}

async function parse<T>(res: Response): Promise<T> {
  const text = await res.text()
  let data: unknown = null
  if (text) {
    try {
      data = JSON.parse(text)
    } catch {
      data = text
    }
  }
  if (!res.ok) {
    if (res.status === 401 && onUnauthorized) {
      onUnauthorized()
    }
    const msg =
      data && typeof data === 'object' && data !== null
        ? 'detail' in data && (data as { detail: unknown }).detail != null
          ? String((data as { detail: unknown }).detail)
          : 'title' in data && (data as { title: unknown }).title != null
            ? String((data as { title: unknown }).title)
            : 'message' in data && (data as { message: unknown }).message != null
              ? String((data as { message: unknown }).message)
              : `HTTP ${res.status}`
        : `HTTP ${res.status}`
    throw new ApiError(res.status, msg, data)
  }
  return data as T
}

