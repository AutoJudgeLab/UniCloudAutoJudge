import { useQuery } from '@tanstack/react-query'
import { ArrowLeft, CalendarDays, Target } from 'lucide-react'
import { Link, useOutletContext, useParams } from 'react-router-dom'
import { request } from '../api/client'
import type { Assignment, User } from '../api/types'
import { Failure, Loading } from '../components/Feedback'
import { copy } from '../copy'
import { date, score, Status } from './Assignments'
import { SubmitSolution } from '../components/SubmitSolution'
import { TestCases } from '../components/TestCases'
import { canManage } from '../components/Management'
import { submissionCopy } from '../submissionCopy'

export function AssignmentDetail() {
  const { id } = useParams()
  const user = useOutletContext<User>()
  const result = useQuery({
    queryKey: ['assignment', user.id, user.role, id],
    queryFn: ({ signal }) =>
      request<Assignment>(`/api/assignments/${encodeURIComponent(id ?? '')}`, { signal }),
  })
  return (
    <>
      <Link className="back-link" to="/assignments">
        <ArrowLeft size={16} aria-hidden="true" />
        {copy.back}
      </Link>
      {result.isPending ? (
        <Loading />
      ) : result.isError ? (
        <Failure error={result.error} retry={() => void result.refetch()} />
      ) : (
        <>
          <div className="page-heading detail-heading">
            <div>
              <Status value={result.data.status} />
              <h1>{result.data.title}</h1>
            </div>
          </div>
          <div className="detail-meta">
            <div>
              <CalendarDays size={20} aria-hidden="true" />
              <span>
                {copy.deadline}
                <strong>{date(result.data.deadline)}</strong>
              </span>
            </div>
            <div>
              <Target size={20} aria-hidden="true" />
              <span>
                {copy.score}
                <strong>{score(result.data.maximumScore)}</strong>
              </span>
            </div>
          </div>
          <section className="content-panel">
            <h2>{copy.statement}</h2>
            <div className="statement">{result.data.description}</div>
          </section>
          <div className="action-row assignment-actions">
            {canManage(user, result.data) && (
              <Link className="button" to={'/assignments/' + id + '/manage'}>
                Управление на заданието
              </Link>
            )}
            <Link className="button secondary" to={`/assignments/${id}/submissions`}>
              {submissionCopy.history}
            </Link>
            <Link className="button secondary" to={`/assignments/${id}/leaderboard`}>
              {submissionCopy.leaderboard}
            </Link>
          </div>
          {user.role === 'STUDENT' && <TestCases assignment={result.data} user={user} />}
          {user.role === 'STUDENT' && (
            <SubmitSolution
              key={`${user.id}:${result.data.id}`}
              assignment={result.data}
              user={user}
            />
          )}
        </>
      )}
    </>
  )
}
