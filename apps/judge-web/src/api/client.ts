export class ApiError extends Error {
  constructor(
    public status: number,
    public code?: string,
  ) {
    super(`API request failed (${status})`)
  }
}

// No bearer tokens in browser storage. Cookies and CSRF are managed by the API.
export async function request<T>(path: string, options: RequestInit = {}): Promise<T> {
  const response = await fetch(path, {
    ...options,
    credentials: 'same-origin',
    headers: { Accept: 'application/json', ...options.headers },
  })
  if (!response.ok) {
    const problem = (await response.json().catch(() => ({}))) as { code?: string }
    throw new ApiError(response.status, problem.code)
  }
  if (response.status === 204) return undefined as T
  return response.json() as Promise<T>
}

export async function logout() {
  await mutate<void>('/auth/logout')
}

export async function mutate<T>(path: string, options: RequestInit = {}): Promise<T> {
  // A fresh CSRF token belongs to the current session, including after a new login.
  const csrf = await request<{ headerName: string; token: string }>('/auth/csrf', {
    cache: 'no-store',
  })
  return request<T>(path, {
    method: 'POST',
    ...options,
    headers: { ...options.headers, [csrf.headerName]: csrf.token },
  })
}
