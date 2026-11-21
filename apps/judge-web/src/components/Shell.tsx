import { useState } from 'react'
import {
  BookOpen,
  CodeXml,
  LogOut,
  ShieldCheck,
  UserRound,
  ArrowUpRight,
  FileCode2,
} from 'lucide-react'
import { Link, NavLink, Outlet, useNavigate } from 'react-router-dom'
import { logout, ApiError } from '../api/client'
import { queryClient, sessionKey } from '../api/queries'
import type { User } from '../api/types'
import { copy } from '../copy'
import { submissionCopy } from '../submissionCopy'

export function Shell({ user }: { user: User }) {
  const navigate = useNavigate()
  const [busy, setBusy] = useState(false)
  const [failed, setFailed] = useState(false)
  async function signOut() {
    setBusy(true)
    setFailed(false)
    try {
      await logout()
    } catch (error) {
      if (!(error instanceof ApiError && error.status === 401)) {
        setFailed(true)
        setBusy(false)
        return
      }
    }
    await queryClient.cancelQueries()
    queryClient.clear()
    queryClient.setQueryData(sessionKey, null)
    navigate('/login', { replace: true })
  }
  return (
    <div className="app-layout">
      <a className="skip-link" href="#main">
        {copy.skip}
      </a>
      <aside className="sidebar">
        <Link to="/assignments" className="brand">
          <span className="brand-icon">
            <CodeXml size={25} aria-hidden="true" />
          </span>
          <span>
            {copy.app}
            <small>UNICLOUD</small>
          </span>
        </Link>
        <div className="sidebar-label">РАБОТНО ПРОСТРАНСТВО</div>
        <nav aria-label={copy.accountMenu}>
          <NavLink to="/assignments">
            <BookOpen size={19} aria-hidden="true" />
            {copy.assignments}
            <ArrowUpRight className="nav-arrow" size={16} aria-hidden="true" />
          </NavLink>
          {user.role === 'STUDENT' && (
            <NavLink to="/submissions">
              <FileCode2 size={19} aria-hidden="true" />
              {submissionCopy.mine}
            </NavLink>
          )}
          {user.role === 'ADMIN' && (
            <NavLink to="/admin/users">
              <ShieldCheck size={19} aria-hidden="true" />
              Потребители
            </NavLink>
          )}
          <NavLink to="/profile">
            <UserRound size={19} aria-hidden="true" />
            {copy.profile}
          </NavLink>
        </nav>
        <div className="sidebar-note">
          <ShieldCheck size={20} aria-hidden="true" />
          <span>
            {copy.portal}
            <small>{copy.roles[user.role]}</small>
          </span>
        </div>
        <div className="sidebar-user">
          <div className="avatar" aria-hidden="true">
            {user.username.slice(0, 2).toUpperCase()}
          </div>
          <div>
            <strong>{user.username}</strong>
            <small>{copy.roles[user.role]}</small>
          </div>
        </div>
        <button className="logout" onClick={() => void signOut()} disabled={busy}>
          <LogOut size={17} aria-hidden="true" />
          {busy ? copy.loggingOut : copy.logout}
        </button>
        {failed && (
          <p className="logout-error" role="alert">
            {copy.logoutError}
          </p>
        )}
      </aside>
      <div className="workspace">
        <header className="topbar">
          <span>
            UniCloud <span className="divider">/</span> <strong>{copy.app}</strong>
          </span>
          <span className="role-pill">
            <ShieldCheck size={14} aria-hidden="true" />
            {copy.roles[user.role]}
          </span>
        </header>
        <main id="main" tabIndex={-1}>
          <Outlet context={user} />
        </main>
        <footer>UniCloud · Auto Judge</footer>
      </div>
    </div>
  )
}
