import { useQuery } from '@tanstack/react-query'
import { useState, type FormEvent } from 'react'
import { Link, useOutletContext, useParams, useSearchParams } from 'react-router-dom'
import { ApiError, mutate, request } from '../api/client'
import { queryClient } from '../api/queries'
import type { User, UserPage } from '../api/types'
import { Failure, Loading } from '../components/Feedback'
import { ActionError, useAction } from '../components/Management'
import { Pagination, pageNumber } from '../components/Pagination'
import { copy } from '../copy'
import { date } from './Assignments'

function SearchForm({
  params,
  apply,
}: {
  params: URLSearchParams
  apply: (values: Record<string, string>) => void
}) {
  const [username, setUsername] = useState(params.get('username') ?? '')
  const [email, setEmail] = useState(params.get('email') ?? '')
  function submit(event: FormEvent) {
    event.preventDefault()
    apply({ username, email })
  }
  return (
    <form className="user-search" onSubmit={submit}>
      <label htmlFor="user-name-filter">
        Потребителско име
        <input
          id="user-name-filter"
          maxLength={100}
          value={username}
          onChange={(e) => setUsername(e.target.value)}
        />
      </label>
      <label htmlFor="user-email-filter">
        Имейл
        <input
          id="user-email-filter"
          maxLength={254}
          value={email}
          onChange={(e) => setEmail(e.target.value)}
        />
      </label>
      <button type="submit" className="button">
        Търси
      </button>
    </form>
  )
}

export function AdminUsers() {
  const user = useOutletContext<User>()
  const [params, setParams] = useSearchParams()
  const page = pageNumber(params.get('page'))
  const role = Object.keys(copy.roles).includes(params.get('role') ?? '') ? params.get('role')! : ''
  const enabled = ['true', 'false'].includes(params.get('enabled') ?? '')
    ? params.get('enabled')!
    : ''
  const sort = ['createdAt,desc', 'username,asc', 'email,asc'].includes(params.get('sort') ?? '')
    ? params.get('sort')!
    : 'createdAt,desc'
  const query = new URLSearchParams({ page: String(page), size: '12', sort })
  for (const [key, value] of Object.entries({
    role,
    enabled,
    username: (params.get('username') ?? '').slice(0, 100),
    email: (params.get('email') ?? '').slice(0, 254),
  }))
    if (value) query.set(key, value)
  const result = useQuery({
    queryKey: ['admin-users', user.id, query.toString()],
    queryFn: ({ signal }) => request<UserPage>('/api/admin/users?' + query, { signal }),
    enabled: user.role === 'ADMIN',
  })
  function change(values: Record<string, string>) {
    const next = new URLSearchParams(params)
    for (const [key, value] of Object.entries(values)) {
      if (value) next.set(key, value)
      else next.delete(key)
    }
    if (!('page' in values)) next.delete('page')
    setParams(next)
  }
  if (user.role !== 'ADMIN') return <Failure error={new ApiError(403)} />
  return (
    <>
      <div className="page-heading">
        <div>
          <p className="eyebrow">АДМИНИСТРАЦИЯ</p>
          <h1>Потребители</h1>
          <p>Потребители, създадени или синхронизирани в Judge.</p>
        </div>
      </div>
      <p className="inline-note">
        Това не е каталог на всички Portal акаунти. Ролите и регистрацията се управляват в UniCloud
        Portal.
      </p>
      <section className="content-panel">
        <SearchForm
          key={params.get('username') + ':' + params.get('email')}
          params={params}
          apply={change}
        />
        <div className="filters admin-filters">
          <select aria-label="Роля" value={role} onChange={(e) => change({ role: e.target.value })}>
            <option value="">Всички роли</option>
            {Object.entries(copy.roles).map(([value, label]) => (
              <option key={value} value={value}>
                {label}
              </option>
            ))}
          </select>
          <select
            aria-label="Достъп"
            value={enabled}
            onChange={(e) => change({ enabled: e.target.value })}
          >
            <option value="">Всички потребители</option>
            <option value="true">Активни</option>
            <option value="false">Спрени</option>
          </select>
          <select
            aria-label="Подреждане"
            value={sort}
            onChange={(e) => change({ sort: e.target.value })}
          >
            <option value="createdAt,desc">Най-нови</option>
            <option value="username,asc">По име</option>
            <option value="email,asc">По имейл</option>
          </select>
          <Link className="text-button" to="/admin/users">
            Изчисти филтрите
          </Link>
        </div>
      </section>
      {result.isPending ? (
        <Loading />
      ) : result.isError ? (
        <Failure error={result.error} retry={() => void result.refetch()} />
      ) : (
        <>
          <p className="muted">Намерени: {result.data.totalElements}</p>
          {!result.data.content.length && (
            <div className="empty-state">
              <h2>Няма намерени потребители</h2>
              <p>Проверете филтрите или изчакайте първия вход през Portal.</p>
            </div>
          )}
          <div className="user-list">
            {result.data.content.map((entry) => (
              <article className="content-panel user-row" key={entry.id}>
                <div>
                  <h2>
                    <Link to={'/admin/users/' + entry.id}>{entry.username}</Link>
                  </h2>
                  <p>{entry.email ?? 'Няма имейл'}</p>
                </div>
                <span>{copy.roles[entry.role]}</span>
                <span
                  className={'status ' + (entry.enabled ? 'status-published' : 'status-archived')}
                >
                  {entry.enabled ? 'Активен' : 'Спрян'}
                </span>
                <Link className="button secondary" to={'/admin/users/' + entry.id}>
                  Профил
                </Link>
              </article>
            ))}
          </div>
          <Pagination
            page={page}
            totalPages={result.data.totalPages}
            onPage={(value) => change({ page: String(value) })}
          />
        </>
      )}
    </>
  )
}

export function AdminUserDetail() {
  const { id } = useParams()
  const actor = useOutletContext<User>()
  const action = useAction()
  const [notice, setNotice] = useState('')
  const result = useQuery({
    queryKey: ['admin-user', actor.id, id],
    queryFn: ({ signal }) =>
      request<User>('/api/admin/users/' + encodeURIComponent(id!), { signal }),
    enabled: actor.role === 'ADMIN',
  })
  if (actor.role !== 'ADMIN') return <Failure error={new ApiError(403)} />
  if (result.isPending) return <Loading />
  if (result.isError) return <Failure error={result.error} retry={() => void result.refetch()} />
  const user = result.data
  function changeStatus() {
    void action.run(
      async () => {
        const updated = await mutate<User>('/api/admin/users/' + user.id + '/status', {
          method: 'PATCH',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ enabled: !user.enabled }),
        })
        queryClient.setQueryData(['admin-user', actor.id, id], updated)
        await queryClient.invalidateQueries({ queryKey: ['admin-users'] })
        setNotice(updated.enabled ? 'Достъпът до Judge е активиран.' : 'Достъпът до Judge е спрян.')
      },
      user.enabled
        ? 'Да спра ли достъпа на ' +
            user.username +
            ' до Judge? Това не изтрива предаванията и не спира Portal акаунта.'
        : 'Да активирам ли достъпа на ' + user.username + ' до Judge?',
    )
  }
  return (
    <>
      <Link className="back-link" to="/admin/users">
        ← Към потребителите
      </Link>
      <div className="page-heading">
        <div>
          <p className="eyebrow">ПОТРЕБИТЕЛ В JUDGE</p>
          <h1>{user.username}</h1>
        </div>
      </div>
      <section className="content-panel">
        <dl className="admin-profile">
          <div>
            <dt>Имейл</dt>
            <dd>{user.email ?? '—'}</dd>
          </div>
          <div>
            <dt>Роля</dt>
            <dd>{copy.roles[user.role]}</dd>
          </div>
          <div>
            <dt>Достъп до Judge</dt>
            <dd>{user.enabled ? 'Активен' : 'Спрян'}</dd>
          </div>
          <div>
            <dt>Създаден в Judge</dt>
            <dd>{date(user.createdAt)}</dd>
          </div>
        </dl>
        <p className="muted">Ролята се управлява в Portal. Тук променяте само достъпа до Judge.</p>
        {user.id === actor.id ? (
          <p className="inline-note">
            Това е вашият профил. За спиране на собствения ви достъп използвайте друг администратор.
          </p>
        ) : (
          <button
            className={'button ' + (user.enabled ? 'danger' : '')}
            disabled={action.busy}
            onClick={changeStatus}
          >
            {action.busy ? 'Запазване…' : user.enabled ? 'Спри достъпа' : 'Активирай достъпа'}
          </button>
        )}
        <ActionError error={action.error} />
        {notice && <p role="status">{notice}</p>}
      </section>
    </>
  )
}
