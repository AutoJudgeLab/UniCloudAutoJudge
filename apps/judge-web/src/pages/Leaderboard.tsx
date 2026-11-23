import { useQuery } from '@tanstack/react-query'
import { Trophy, ArrowLeft } from 'lucide-react'
import { Link, useOutletContext, useParams, useSearchParams } from 'react-router-dom'
import { request } from '../api/client'
import type { Leaderboard as LeaderboardData, User } from '../api/types'
import { Loading, Failure } from '../components/Feedback'
import { Pagination, pageNumber } from '../components/Pagination'
import { submissionCopy as text } from '../submissionCopy'
import { score } from './Assignments'

export function Leaderboard() {
  const { id } = useParams()
  const user = useOutletContext<User>()
  const [params, setParams] = useSearchParams()
  const page = pageNumber(params.get('page'))
  const result = useQuery({
    queryKey: ['leaderboard', user.id, user.role, id, page],
    queryFn: ({ signal }) =>
      request<LeaderboardData>(
        `/api/assignments/${encodeURIComponent(id ?? '')}/leaderboard?page=${page}&size=20`,
        { signal },
      ),
  })
  return (
    <>
      <Link className="back-link" to={`/assignments/${id}`}>
        <ArrowLeft size={16} aria-hidden="true" />
        {text.assignment}
      </Link>
      <div className="page-heading">
        <div>
          <p className="eyebrow">НАЙ-ДОБРИ РЕЗУЛТАТИ</p>
          <h1>{text.leaderboard}</h1>
          <p>{text.leaderboardDescription}</p>
        </div>
        <span className="heading-icon">
          <Trophy size={28} aria-hidden="true" />
        </span>
      </div>
      {result.isPending ? (
        <Loading />
      ) : result.isError ? (
        <Failure error={result.error} retry={() => void result.refetch()} />
      ) : (
        <>
          {result.data.content.length ? (
            <div className="table-scroll content-panel">
              <table className="ranking">
                <caption className="sr-only">{text.leaderboard}</caption>
                <thead>
                  <tr>
                    <th scope="col">{text.rank}</th>
                    <th scope="col">{text.participant}</th>
                    <th scope="col">{text.score}</th>
                  </tr>
                </thead>
                <tbody>
                  {result.data.content.map((entry) => (
                    <tr key={entry.rank}>
                      <td>{entry.rank}</td>
                      <th scope="row">{entry.username}</th>
                      <td>{score(entry.bestScore)}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          ) : (
            <div className="empty-state">
              <Trophy size={30} aria-hidden="true" />
              <h2>{text.noRanking}</h2>
            </div>
          )}
          <Pagination
            page={page}
            totalPages={result.data.totalPages}
            onPage={(value) => setParams({ page: String(value) })}
          />
        </>
      )}
    </>
  )
}
