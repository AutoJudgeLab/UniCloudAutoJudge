import { ArrowRight, CodeXml, ShieldCheck } from 'lucide-react'
import { Navigate, useSearchParams } from 'react-router-dom'
import { useSession } from '../api/queries'
import { Loading, Failure } from '../components/Feedback'
import { copy } from '../copy'

export function Login() {
  const session = useSession()
  const [params] = useSearchParams()
  if (session.isPending) return <Loading session />
  if (session.data && !session.isError) return <Navigate to="/assignments" replace />
  return (
    <main className="login-layout">
      <section className="login-story">
        <div className="brand">
          <span className="brand-icon">
            <CodeXml aria-hidden="true" />
          </span>
          <span>
            {copy.app}
            <small>UNICLOUD</small>
          </span>
        </div>
        <div className="login-intro">
          <p className="eyebrow">{copy.loginEyebrow}</p>
          <h1>{copy.loginTitle}</h1>
          <p>{copy.loginDescription}</p>
        </div>
        <ol className="login-steps">
          {copy.steps.map(([title, text], i) => (
            <li key={title}>
              <span>0{i + 1}</span>
              <div>
                <strong>{title}</strong>
                <p>{text}</p>
              </div>
            </li>
          ))}
        </ol>
        <small className="login-footer">UniCloud / Пространство за следващото ви решение.</small>
      </section>
      <section className="login-action" aria-labelledby="login-heading">
        <div className="login-card">
          <div className="large-icon">
            <CodeXml size={32} aria-hidden="true" />
          </div>
          <p className="eyebrow">ДА ЗАПОЧВАМЕ</p>
          <h2 id="login-heading">{copy.loginCardTitle}</h2>
          <p>{copy.loginCardDescription}</p>
          {params.get('error') && (
            <p className="inline-error" role="alert">
              {copy.loginFailed}
            </p>
          )}
          {params.get('session') === 'expired' && (
            <p className="inline-note" role="status">
              {copy.sessionExpired}
            </p>
          )}
          {session.isError && (
            <Failure error={session.error} retry={() => void session.refetch()} />
          )}
          <a className="button login-button" href="/oauth2/authorization/portal">
            {copy.login}
            <ArrowRight size={18} aria-hidden="true" />
          </a>
          <div className="login-note">
            <ShieldCheck size={17} aria-hidden="true" />
            <span>{copy.loginNote}</span>
          </div>
        </div>
      </section>
    </main>
  )
}
