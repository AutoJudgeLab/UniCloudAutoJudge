import { QueryCache, QueryClient, useQuery } from '@tanstack/react-query'
import { ApiError, request } from './client'
import type { User } from './types'

export const sessionKey = ['session'] as const
export const queryClient = new QueryClient({
  queryCache: new QueryCache({
    onError(error) {
      if (error instanceof ApiError && error.status === 401) {
        queryClient.setQueryData(sessionKey, null)
        // Keep the observed session query so mounted route guards receive this update.
        queryClient.removeQueries({ predicate: (query) => query.queryKey[0] !== 'session' })
      }
    },
  }),
  defaultOptions: {
    queries: {
      staleTime: 30_000,
      retry: (attempt, error) =>
        attempt < 1 && (!(error instanceof ApiError) || error.status >= 500),
      refetchOnWindowFocus: true,
    },
  },
})

export function useSession() {
  return useQuery({
    queryKey: sessionKey,
    queryFn: async ({ signal }) => {
      try {
        return await request<User>('/api/users/me', { signal, cache: 'no-store' })
      } catch (error) {
        if (error instanceof ApiError && error.status === 401) {
          queryClient.removeQueries({ predicate: (query) => query.queryKey[0] !== 'session' })
          return null
        }
        throw error
      }
    },
    staleTime: 0,
    refetchInterval: 60_000,
  })
}
