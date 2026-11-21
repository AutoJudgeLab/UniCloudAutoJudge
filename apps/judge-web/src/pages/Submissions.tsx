import { useQuery } from '@tanstack/react-query'
import { FileCode2, ArrowRight } from 'lucide-react'
import { Link, useOutletContext, useParams, useSearchParams } from 'react-router-dom'
import { request } from '../api/client'
import type { SubmissionPage, User } from '../api/types'
import { Loading, Failure } from '../components/Feedback'
import { Pagination, pageNumber } from '../components/Pagination'
import { SubmissionState, languageName } from '../components/SubmissionState'
import { submissionCopy as text } from '../submissionCopy'
import { copy } from '../copy'
import { date } from './Assignments'

export function Submissions() {
  const user = useOutletContext<User>()
  const { id } = useParams()
  const [params, setParams] = useSearchParams()
  const page = pageNumber(params.get('page'))
  const selectedStatus = params.get('status') ?? ''
  const status = Object.keys(text.statuses).includes(selectedStatus) ? selectedStatus : ''
  const selectedLanguage = params.get('language') ?? ''
  const language = Object.keys(text.languages).includes(selectedLanguage) ? selectedLanguage : ''
  const sort = params.get('sort') === 'submittedAt,asc' ? 'submittedAt,asc' : 'submittedAt,desc'
  const query = new URLSearchParams({ page: String(page), size: '12', sort })
  if (status) query.set('status', status)
  if (language) query.set('programmingLanguage', language)
  const path = id
    ? `/api/assignments/${encodeURIComponent(id)}/submissions`
    : '/api/users/me/submissions'
  const result = useQuery({
    queryKey: ['submissions', user.id, user.role, id, page, status, language, sort],
    queryFn: ({ signal }) => request<SubmissionPage>(`${path}?${query}`, { signal }),
  })
  function change(key: string, value: string) {
    const next = new URLSearchParams(params)
    next.set(key, value)
    if (key !== 'page') next.delete('page')
    setParams(next)
  }
  return (
    <>
      <div className="page-heading">
        <div>
          <p className="eyebrow">ВАШИТЕ РЕШЕНИЯ</p>
          <h1>{id ? text.assignmentHistory : text.mine}</h1>
          <p>{id ? text.filterApplied : text.historyDescription}</p>
        </div>
        <span className="heading-icon">
          <FileCode2 size={28} aria-hidden="true" />
        </span>
      </div>
      {id && (
        <Link className="back-link" to={`/assignments/${id}`}>
          {text.assignment}
        </Link>
      )}
      <div className="list-toolbar">
        <span className="list-count">
          {result.isSuccess && <strong>{result.data.totalElements}</strong>}
          {text.history}
        </span>
        <div className="filters history-filters">
          <select
            aria-label={text.status}
            value={status}
            onChange={(e) => change('status', e.target.value)}
          >
            <option value="">{text.allStatuses}</option>
            {Object.entries(text.statuses).map(([value, label]) => (
              <option key={value} value={value}>
                {label}
              </option>
            ))}
          </select>
          <select
            aria-label={text.language}
            value={language}
            onChange={(e) => change('language', e.target.value)}
          >
            <option value="">{text.allLanguages}</option>
            {Object.entries(text.languages).map(([value, label]) => (
              <option key={value} value={value}>
                {label}
              </option>
            ))}
          </select>
          <select
            aria-label={copy.sort}
            value={sort}
            onChange={(e) => change('sort', e.target.value)}
          >
            <option value="submittedAt,desc">{text.sortNewest}</option>
            <option value="submittedAt,asc">{text.sortOldest}</option>
          </select>
        </div>
      </div>
      {result.isPending ? (
        <Loading />
      ) : result.isError ? (
        <Failure error={result.error} retry={() => void result.refetch()} />
      ) : (
        <>
          {!result.data.content.length ? (
            <div className="empty-state">
              <FileCode2 size={30} aria-hidden="true" />
              <h2>{text.noSubmissions}</h2>
              <p>{text.emptyHistory}</p>
            </div>
          ) : (
            <div className="submission-list">
              {result.data.content.map((submission) => (
                <article className="submission-row" key={submission.submissionId}>
                  <div className="submission-file">
                    <FileCode2 size={23} aria-hidden="true" />
                    <div>
                      <h2>
                        <Link to={`/submissions/${submission.submissionId}`}>
                          {submission.originalFilename}
                        </Link>
                      </h2>
                      <span>{languageName(submission.programmingLanguage)}</span>
                    </div>
                  </div>
                  <SubmissionState status={submission.status} />
                  <time dateTime={submission.submittedAt}>{date(submission.submittedAt)}</time>
                  <Link
                    className="open-assignment"
                    to={`/submissions/${submission.submissionId}`}
                    aria-label={`${text.viewResult}: ${submission.originalFilename}`}
                  >
                    <ArrowRight size={20} aria-hidden="true" />
                  </Link>
                </article>
              ))}
            </div>
          )}
          <Pagination
            page={page}
            totalPages={result.data.totalPages}
            onPage={(value) => change('page', String(value))}
          />
        </>
      )}
    </>
  )
}
