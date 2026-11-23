import { createContext, useContext, useRef, useState, type ReactNode } from 'react'
import { ApiError } from '../api/client'
import { queryClient } from '../api/queries'
import type { Assignment, User } from '../api/types'
import { managementCopy as text } from '../managementCopy'
import { Failure } from './Feedback'

export const canManage = (user: User, assignment: Assignment) =>
  user.role === 'ADMIN' || (user.role === 'TEACHER' && assignment.teacherId === user.id)

export async function refreshAssignments() {
  await Promise.all(
    ['assignments', 'assignment', 'test-cases'].map((key) =>
      queryClient.invalidateQueries({ queryKey: [key] }),
    ),
  )
}

// Decimal strings keep submitted points exact; avoid rounding through a JS number.
export function points(value: string): string {
  const canonical = value.trim().replace(',', '.')
  if (!/^\d{1,15}(\.\d{1,4})?$/.test(canonical) || Number(canonical) <= 0)
    throw new Error(text.invalid)
  return canonical
}

const ActionContext = createContext<{
  lock: { current: boolean }
  busy: boolean
  setBusy: (busy: boolean) => void
} | null>(null)

export function ActionScope({ children }: { children: ReactNode }) {
  const lock = useRef(false)
  const [busy, setBusy] = useState(false)
  return <ActionContext.Provider value={{ lock, busy, setBusy }}>{children}</ActionContext.Provider>
}

export function useAction() {
  const scope = useContext(ActionContext)
  const localLock = useRef(false)
  const lock = scope?.lock ?? localLock
  const uncertain = useRef(false)
  const [localBusy, setLocalBusy] = useState(false)
  const busy = scope?.busy ?? localBusy
  const setBusy = scope?.setBusy ?? setLocalBusy
  const [error, setError] = useState<unknown>(null)
  async function run(action: () => Promise<void>, confirm?: string) {
    if (lock.current) return
    if (confirm && !window.confirm(confirm)) return
    if (uncertain.current && !window.confirm(text.uncertain)) return
    lock.current = true
    setBusy(true)
    setError(null)
    try {
      await action()
      uncertain.current = false
    } catch (failure) {
      uncertain.current =
        failure instanceof TypeError || (failure instanceof ApiError && failure.status >= 500)
      setError(failure)
    } finally {
      lock.current = false
      setBusy(false)
    }
  }
  return { busy, error, run }
}

export function ActionError({ error }: { error: unknown }) {
  if (!error) return null
  if (error instanceof ApiError) {
    const message = error.code && text.problem[error.code]
    if (message)
      return (
        <p className="inline-error" role="alert">
          {message}
        </p>
      )
    if ([401, 403, 404].includes(error.status)) return <Failure error={error} />
    return (
      <p className="inline-error" role="alert">
        {error.status >= 500
          ? text.unknown
          : 'Операцията е отказана. Проверете стойностите и презаредете данните.'}
      </p>
    )
  }
  return (
    <p className="inline-error" role="alert">
      {error instanceof TypeError
        ? text.unknown
        : error instanceof Error
          ? error.message
          : text.unknown}
    </p>
  )
}
