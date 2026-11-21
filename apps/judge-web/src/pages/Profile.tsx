import { ShieldCheck } from 'lucide-react'
import { useOutletContext } from 'react-router-dom'
import type { User } from '../api/types'
import { copy } from '../copy'
import { date } from './Assignments'

export function Profile() {
  const user = useOutletContext<User>()
  return (
    <>
      <div className="page-heading">
        <div>
          <p className="eyebrow">UNICLOUD</p>
          <h1>{copy.account}</h1>
          <p>{copy.accountDescription}</p>
        </div>
      </div>
      <section className="content-panel profile-panel">
        <div className="profile-heading">
          <div className="avatar">{user.username.slice(0, 2).toUpperCase()}</div>
          <div>
            <h2>{user.username}</h2>
            <span className="role-pill">{copy.roles[user.role]}</span>
          </div>
        </div>
        <dl>
          {[
            [copy.username, user.username],
            [copy.email, user.email || copy.missing],
            [copy.role, copy.roles[user.role]],
            [copy.created, date(user.createdAt)],
          ].map(([label, value]) => (
            <div key={label}>
              <dt>{label}</dt>
              <dd>{value}</dd>
            </div>
          ))}
        </dl>
        <p className="profile-note">
          <ShieldCheck size={18} aria-hidden="true" />
          {copy.managedByPortal}
        </p>
      </section>
    </>
  )
}
