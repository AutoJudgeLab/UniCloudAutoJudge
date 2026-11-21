import { AlertCircle, ArrowRight, LoaderCircle } from 'lucide-react'
import { ApiError } from '../api/client'
import { copy } from '../copy'

export function Loading({ session = false }: { session?: boolean }) {
  return (
    <div className="feedback" role="status">
      <LoaderCircle className="spin" aria-hidden="true" />
      <p>{session ? copy.sessionLoading : copy.loading}</p>
    </div>
  )
}

export function Failure({ error, retry }: { error: unknown; retry?: () => void }) {
  const status = error instanceof ApiError ? error.status : undefined
  const message =
    status === 401
      ? copy.sessionExpired
      : status === 403
        ? copy.forbidden
        : status === 404
          ? copy.notFound
          : status && status >= 500
            ? copy.unavailable
            : error instanceof TypeError
              ? copy.networkError
              : copy.genericError
  return (
    <div className="feedback error-panel" role="alert">
      <AlertCircle aria-hidden="true" />
      <p>{message}</p>
      {status === 401 ? (
        <a className="button" href="/oauth2/authorization/portal">
          {copy.login}
          <ArrowRight size={16} aria-hidden="true" />
        </a>
      ) : (
        retry &&
        status !== 403 &&
        status !== 404 && (
          <button className="button secondary" onClick={retry}>
            {copy.retry}
          </button>
        )
      )}
    </div>
  )
}
