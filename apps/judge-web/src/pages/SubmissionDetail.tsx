import { useQuery } from '@tanstack/react-query'
import { ArrowLeft, FileCode2, Clock3, RefreshCw } from 'lucide-react'
import { useEffect } from 'react'
import { Link, useOutletContext, useParams } from 'react-router-dom'
import { request } from '../api/client'
import type { Evaluation, Submission, TestResult, User } from '../api/types'
import { Failure, Loading } from '../components/Feedback'
import { SubmissionState, isEvaluating, languageName } from '../components/SubmissionState'
import { submissionCopy as text } from '../submissionCopy'
import { date, score } from './Assignments'
import { queryClient } from '../api/queries'

function TestOutcome({ result, number }: { result: TestResult; number: number }) {
  const hidden =
    result.inputData == null && result.expectedOutput == null && result.actualOutput == null
  return (
    <details className="test-outcome">
      <summary>
        <span>Тест {number}</span>
        <span className={result.status === 'ACCEPTED' ? 'positive' : 'negative'}>
          {text.verdicts[result.status]}
        </span>
        <strong>{result.awardedPoints == null ? '—' : score(result.awardedPoints)} т.</strong>
      </summary>
      <div className="test-body">
        {hidden ? (
          <p className="muted">{text.hidden}</p>
        ) : (
          <div className="test-values">
            {[
              [text.input, result.inputData],
              [text.expected, result.expectedOutput],
              [text.actual, result.actualOutput],
            ].map(([label, value]) => (
              <div key={label}>
                <h3>{label}</h3>
                <pre>{value === null ? '—' : value === '' ? text.emptyValue : value}</pre>
              </div>
            ))}
          </div>
        )}
        <p className="muted">
          {text.time}: {result.executionTimeMs == null ? '—' : `${result.executionTimeMs} ms`}
        </p>
      </div>
    </details>
  )
}

export function SubmissionDetail() {
  const { id } = useParams()
  const user = useOutletContext<User>()
  const path = `/api/submissions/${encodeURIComponent(id ?? '')}`
  const submission = useQuery({
    queryKey: ['submission', user.id, user.role, id],
    queryFn: ({ signal }) => request<Submission>(path, { signal }),
  })
  const evaluation = useQuery({
    queryKey: ['evaluation', user.id, user.role, id],
    queryFn: ({ signal }) => request<Evaluation>(`${path}/result`, { signal }),
    enabled: submission.isSuccess,
    refetchInterval: (query) =>
      query.state.error ? false : isEvaluating(query.state.data?.status) ? 3000 : false,
    refetchIntervalInBackground: false,
    refetchOnWindowFocus: false,
  })
  const evaluatedStatus = evaluation.data?.status
  useEffect(() => {
    if (evaluatedStatus && !isEvaluating(evaluatedStatus)) {
      void queryClient.invalidateQueries({ queryKey: ['submissions'] })
      void queryClient.invalidateQueries({ queryKey: ['leaderboard'] })
    }
  }, [evaluatedStatus])
  if (submission.isPending) return <Loading />
  if (submission.isError)
    return <Failure error={submission.error} retry={() => void submission.refetch()} />
  const data = evaluation.data
  const status = data?.status ?? submission.data.status
  const message =
    status === 'PENDING'
      ? text.queued
      : status === 'RUNNING'
        ? text.running
        : status === 'FAILED'
          ? text.failed
          : status === 'CANCELLED'
            ? text.cancelled
            : data?.verdict === 'COMPILATION_ERROR'
              ? text.compilation
              : data?.verdict === 'INTERNAL_ERROR'
                ? text.internal
                : null
  return (
    <>
      <Link
        className="back-link"
        to={
          user.role === 'STUDENT'
            ? '/submissions'
            : `/assignments/${submission.data.assignmentId}/submissions`
        }
      >
        <ArrowLeft size={16} aria-hidden="true" />
        {text.history}
      </Link>
      <div className="page-heading">
        <div>
          <p className="eyebrow">{text.detail}</p>
          <h1>{submission.data.originalFilename}</h1>
          <p>
            {languageName(submission.data.programmingLanguage)} · {text.submitted}{' '}
            {date(submission.data.submittedAt)}
          </p>
        </div>
        <span className="heading-icon">
          {isEvaluating(status) ? (
            <Clock3 size={28} aria-hidden="true" />
          ) : (
            <FileCode2 size={28} aria-hidden="true" />
          )}
        </span>
      </div>
      <div className="result-topline">
        <SubmissionState status={status} />
        <Link to={`/assignments/${submission.data.assignmentId}`}>{text.assignment}</Link>
      </div>
      {message && (
        <p className="inline-note" role="status">
          {message}
        </p>
      )}
      {evaluation.isError && (
        <>
          <p className="inline-error" role="alert">
            {text.paused}
          </p>
          <Failure error={evaluation.error} retry={() => void evaluation.refetch()} />
        </>
      )}
      {evaluation.isPending ? (
        <Loading />
      ) : (
        data && (
          <>
            <section className="content-panel result-summary" aria-label={text.detail}>
              <h2 className={data.verdict === 'ACCEPTED' ? 'positive' : ''}>
                {data.verdict ? text.verdicts[data.verdict] : text.unavailable}
              </h2>
              <div className="result-metrics">
                <div>
                  <span>{text.score}</span>
                  <strong>
                    {data.totalScore == null ? '—' : score(data.totalScore)}
                    {data.maximumScore == null ? '' : ` / ${score(data.maximumScore)}`}
                  </strong>
                </div>
                <div>
                  <span>{text.tests}</span>
                  <strong>
                    {data.passedTests == null || data.totalTests == null
                      ? '—'
                      : `${data.passedTests} / ${data.totalTests}`}
                  </strong>
                </div>
                <div>
                  <span>{text.time}</span>
                  <strong>
                    {data.executionTimeMs == null ? '—' : `${data.executionTimeMs} ms`}
                  </strong>
                </div>
              </div>
            </section>
            <section className="test-section">
              <h2>{text.testDetails}</h2>
              {data.testResults?.length ? (
                data.testResults.map((result, index) => (
                  <TestOutcome key={result.testCaseId} result={result} number={index + 1} />
                ))
              ) : (
                <p className="muted">{text.noTests}</p>
              )}
            </section>
          </>
        )
      )}
      <button
        className="button secondary"
        disabled={evaluation.isFetching}
        onClick={() => void evaluation.refetch()}
      >
        <RefreshCw size={16} aria-hidden="true" />
        {text.refresh}
      </button>
    </>
  )
}
