import { useAuth } from '@/auth'

/** A failed API call; `message` is the backend's problem detail when it sent one. */
export class ApiError extends Error {
  constructor(
    readonly status: number,
    message: string,
  ) {
    super(message)
  }
}

type Method = 'GET' | 'POST' | 'PUT' | 'PATCH' | 'DELETE'

// Relative /api URLs: same host in prod (Traefik), Vite's proxy in dev.
async function request(method: Method, path: string, body?: unknown): Promise<Response> {
  const token = await useAuth().getToken()
  const headers: Record<string, string> = token ? { Authorization: `Bearer ${token}` } : {}
  let payload: BodyInit | undefined
  if (body instanceof FormData) {
    payload = body // the browser sets the multipart boundary itself
  } else if (body !== undefined) {
    headers['Content-Type'] = 'application/json'
    payload = JSON.stringify(body)
  }
  const response = await fetch(path, { method, headers, body: payload })
  if (!response.ok) {
    let detail: string | undefined
    try {
      detail = (await response.json()).detail
    } catch {
      // no JSON body
    }
    throw new ApiError(response.status, detail ?? `${response.status} ${response.statusText}`)
  }
  return response
}

async function json<T>(response: Response): Promise<T> {
  return response.status === 204 ? (undefined as T) : response.json()
}

export const api = {
  get: async <T>(path: string) => json<T>(await request('GET', path)),
  post: async <T>(path: string, body?: unknown) => json<T>(await request('POST', path, body)),
  put: async <T>(path: string, body?: unknown) => json<T>(await request('PUT', path, body)),
  patch: async <T>(path: string, body?: unknown) => json<T>(await request('PATCH', path, body)),
  delete: async (path: string) => {
    await request('DELETE', path)
  },
}

export function errorMessage(error: unknown): string {
  return error instanceof Error ? error.message : String(error)
}
