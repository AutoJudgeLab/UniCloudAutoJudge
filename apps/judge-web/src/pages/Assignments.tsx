import { useQuery } from '@tanstack/react-query'
import { useEffect, useState } from 'react'
import {
  ArrowDownWideNarrow,
  ArrowRight,
  BookOpen,
  CalendarDays,
  ChevronLeft,
  ChevronRight,
} from 'lucide-react'
import { Link, useOutletContext, useSearchParams } from 'react-router-dom'
import { request } from '../api/client'
import type { AssignmentPage, AssignmentStatus, User } from '../api/types'
import { Failure, Loading } from '../components/Feedback'
import { copy } from '../copy'

export const date = (value: string) =>
  new Intl.DateTimeFormat('bg-BG', { dateStyle: 'medium', timeStyle: 'short' }).format(
    new Date(value),
  )
export const score = (value: number) =>
  new Intl.NumberFormat('bg-BG', { maximumFractionDigits: 4 }).format(value)
export function Status({ value }: { value: AssignmentStatus }) {
  return <span className={`status status-${value.toLowerCase()}`}>{copy.statuses[value]}</span>
}

export function Assignments() {
  const [now, setNow] = useState(() => Date.now())
  useEffect(() => {
    const timer = window.setInterval(() => setNow(Date.now()), 60_000)
    return () => window.clearInterval(timer)
  }, [])
  const user = useOutletContext<User>()
  const [params, setParams] = useSearchParams()
  const requestedPage = Number(params.get('page') ?? 0)
  const page =
    Number.isSafeInteger(requestedPage) && requestedPage >= 0 && requestedPage <= 1_000_000
      ? requestedPage
      : 0
  const requestedStatus = params.get('status') ?? ''
  const status =
    user.role !== 'STUDENT' && Object.keys(copy.statuses).includes(requestedStatus)
      ? requestedStatus
      : ''
  const requestedSort = params.get('sort') ?? ''
  const sort = ['createdAt,desc', 'deadline,asc', 'title,asc'].includes(requestedSort)
    ? requestedSort
    : 'createdAt,desc'
  const query = new URLSearchParams({ page: String(page), size: '12', sort })
  if (status) query.set('status', status)
  const result = useQuery({
    queryKey: ['assignments', user.id, user.role, page, status, sort],
    queryFn: ({ signal }) => request<AssignmentPage>(`/api/assignments?${query}`, { signal }),
  })
  function filter(key: string, value: string) {
    const next = new URLSearchParams(params)
    next.set(key, value)
    next.delete('page')
    setParams(next)
  }
  function changePage(value: number) {
    const next = new URLSearchParams(params)
    next.set('page', String(value))
    setParams(next)
  }
  return (
    <>
      <div className="page-heading">
        <div>
          <p className="eyebrow">РАБОТНО ПРОСТРАНСТВО</p>
          <h1>{copy.assignments}</h1>
          <p>{copy.roleDescriptions[user.role]}</p>
        </div>
        <span className="heading-icon">
          <BookOpen size={28} aria-hidden="true" />
        </span>
      </div>
      {user.role !== 'STUDENT' && (
        <div className="action-row assignment-actions">
          <Link className="button" to="/assignments/new">
            Ново задание
          </Link>
        </div>
      )}
      <div className="list-toolbar">
        <div className="list-count">
          {result.isSuccess ? (
            <>
              <strong>{result.data.totalElements}</strong>
              <span>{copy.total}</span>
            </>
          ) : (
            <span>{copy.assignments}</span>
          )}
        </div>
        <div className="filters">
          {user.role !== 'STUDENT' && (
            <label>
              <span className="sr-only">{copy.status}</span>
              <select value={status} onChange={(e) => filter('status', e.target.value)}>
                <option value="">{copy.allStatuses}</option>
                {Object.entries(copy.statuses).map(([value, label]) => (
                  <option key={value} value={value}>
                    {label}
                  </option>
                ))}
              </select>
            </label>
          )}
          <label className="sort-label">
            <ArrowDownWideNarrow size={16} aria-hidden="true" />
            <span className="sr-only">{copy.sort}</span>
            <select value={sort} onChange={(e) => filter('sort', e.target.value)}>
              <option value="createdAt,desc">{copy.sortNewest}</option>
              <option value="deadline,asc">{copy.sortDeadline}</option>
              <option value="title,asc">{copy.sortTitle}</option>
            </select>
          </label>
        </div>
      </div>
      {result.isPending ? (
        <Loading />
      ) : result.isError ? (
        <Failure error={result.error} retry={() => void result.refetch()} />
      ) : (
        <>
          {result.data.content.length === 0 ? (
            <div className="empty-state">
              <div className="large-icon">
                <BookOpen size={30} aria-hidden="true" />
              </div>
              <h2>{copy.emptyTitle}</h2>
              <p>{status ? copy.filteredEmpty : copy.emptyDescription}</p>
            </div>
          ) : (
            <div className="assignment-list">
              {result.data.content.map((assignment) => (
                <article className="assignment-row" key={assignment.id}>
                  <div className="assignment-icon">
                    <CodeLabel />
                  </div>
                  <div className="assignment-main">
                    <Status value={assignment.status} />
                    <h2>
                      <Link to={`/assignments/${assignment.id}`}>{assignment.title}</Link>
                    </h2>
                    <p className="assignment-preview">{assignment.description}</p>
                  </div>
                  <div className="assignment-deadline">
                    <span>
                      <CalendarDays size={14} aria-hidden="true" />
                      {copy.deadline}
                    </span>
                    <time dateTime={assignment.deadline}>{date(assignment.deadline)}</time>
                    {new Date(assignment.deadline).getTime() < now && (
                      <small>{copy.deadlinePassed}</small>
                    )}
                  </div>
                  <div className="assignment-score">
                    <strong>{score(assignment.maximumScore)}</strong>
                    <span>{copy.points}</span>
                  </div>
                  <Link
                    className="open-assignment"
                    to={`/assignments/${assignment.id}`}
                    aria-label={`${copy.open}: ${assignment.title}`}
                  >
                    <ArrowRight size={20} aria-hidden="true" />
                  </Link>
                </article>
              ))}
            </div>
          )}
          {(result.data.totalPages > 1 || page > 0) && (
            <nav className="pagination" aria-label="Страници със задания">
              <span>
                {copy.page} {page + 1}
                {result.data.totalPages > 0 ? ` ${copy.of} ${result.data.totalPages}` : ''}
              </span>
              <div>
                <button
                  className="button secondary"
                  onClick={() => changePage(page - 1)}
                  disabled={page === 0}
                >
                  <ChevronLeft size={16} aria-hidden="true" />
                  {copy.previous}
                </button>
                <button
                  className="button secondary"
                  onClick={() => changePage(page + 1)}
                  disabled={page + 1 >= result.data.totalPages}
                >
                  {copy.next}
                  <ChevronRight size={16} aria-hidden="true" />
                </button>
              </div>
            </nav>
          )}
        </>
      )}
    </>
  )
}

function CodeLabel() {
  return <span aria-hidden="true">{'</>'}</span>
}
