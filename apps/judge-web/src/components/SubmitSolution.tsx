import { useQuery } from '@tanstack/react-query'
import { useEffect, useRef, useState, type FormEvent } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import { Upload, FileCode2 } from 'lucide-react'
import { ApiError, mutate, request } from '../api/client'
import { queryClient } from '../api/queries'
import { attemptSlot, forgetAttempt, prepareAttempt, savedAttempt } from '../api/submissionAttempt'
import type { Assignment, SubmissionCreated, SubmissionOptions, User } from '../api/types'
import { submissionCopy as text } from '../submissionCopy'
import { Failure, Loading } from './Feedback'

export function SubmitSolution({ assignment, user }: { assignment: Assignment; user: User }) {
  const navigate = useNavigate()
  const slot = attemptSlot(user.id, assignment.id)
  const [selection, setSelection] = useState('')
  const [file, setFile] = useState<File | null>(null)
  const [busy, setBusy] = useState(false)
  const locked = useRef(false)
  const [error, setError] = useState<unknown>(null)
  const [pending, setPending] = useState(() => !!savedAttempt(slot))
  const [frozen, setFrozen] = useState(false)
  const [now, setNow] = useState(() => Date.now())
  const fileInput = useRef<HTMLInputElement>(null)
  useEffect(() => {
    const timer = window.setInterval(() => setNow(Date.now()), 1000)
    return () => window.clearInterval(timer)
  }, [])
  const options = useQuery({
    queryKey: ['submission-options', user.id],
    queryFn: ({ signal }) => request<SubmissionOptions>('/api/submissions/options', { signal }),
  })
  const language =
    options.data?.languages.find((l) => l.value === selection) ?? options.data?.languages[0]
  const closed = new Date(assignment.deadline).getTime() <= now || assignment.status !== 'PUBLISHED'

  async function submit(event: FormEvent) {
    event.preventDefault()
    if (locked.current || !options.data || !language) return
    if (!file) {
      setError(new Error(text.fileRequired))
      return
    }
    locked.current = true
    setBusy(true)
    setError(null)
    try {
      const attempt = await prepareAttempt(slot, file, language, options.data.maxSourceBytes)
      setPending(true)
      setFrozen(true)
      const body = new FormData()
      body.append('file', file)
      body.append('programmingLanguage', language.value)
      const accepted = await mutate<SubmissionCreated>(
        `/api/assignments/${assignment.id}/submissions`,
        { body, headers: { 'Idempotency-Key': attempt.key } },
      )
      forgetAttempt(slot)
      void queryClient.invalidateQueries({ queryKey: ['submissions'] })
      navigate(`/submissions/${accepted.submissionId}`)
    } catch (failure) {
      setError(failure)
    } finally {
      locked.current = false
      setBusy(false)
    }
  }
  function reset() {
    if (pending && !window.confirm(text.discardConfirm)) return
    forgetAttempt(slot)
    setPending(false)
    setFrozen(false)
    setFile(null)
    setError(null)
    if (fileInput.current) fileInput.current.value = ''
  }
  const failureMessage =
    error instanceof ApiError
      ? (text.problem[error.code as keyof typeof text.problem] ??
        (error.status === 413 ? text.tooLarge : text.uploadFailed))
      : error instanceof Error && !(error instanceof TypeError)
        ? error.message
        : text.uploadFailed
  return (
    <section className="content-panel upload-panel" aria-labelledby="submit-heading">
      <div className="section-heading">
        <div>
          <p className="eyebrow">ВАШЕТО РЕШЕНИЕ</p>
          <h2 id="submit-heading">{text.submitTitle}</h2>
        </div>
        <Upload size={25} aria-hidden="true" />
      </div>
      <p className="muted">{text.submitDescription}</p>
      {options.isPending ? (
        <Loading />
      ) : options.isError ? (
        <Failure error={options.error} retry={() => void options.refetch()} />
      ) : !language ? (
        <p>{text.noLanguages}</p>
      ) : (
        <>
          {pending && (
            <p className="inline-note" role="status">
              {frozen ? text.retryNote : text.restoredAttempt}{' '}
              <Link to="/submissions">{text.history}</Link>
            </p>
          )}
          {closed && (
            <p className="inline-note">
              {assignment.status === 'PUBLISHED' ? text.deadline : text.onlyPublished}
            </p>
          )}
          <form onSubmit={(event) => void submit(event)}>
            <div className="upload-fields">
              <label htmlFor="solution-language">
                {text.language}
                <select
                  id="solution-language"
                  value={language.value}
                  disabled={busy || frozen}
                  onChange={(event) => setSelection(event.target.value)}
                >
                  {options.data.languages.map((l) => (
                    <option key={l.value} value={l.value}>
                      {text.languages[l.value]} (.{l.extension})
                    </option>
                  ))}
                </select>
              </label>
              <label htmlFor="solution-file">
                {text.file}
                <input
                  ref={fileInput}
                  id="solution-file"
                  type="file"
                  accept={`.${language.extension}`}
                  disabled={busy || frozen}
                  onChange={(event) => {
                    setFile(event.target.files?.[0] ?? null)
                    setError(null)
                  }}
                  aria-describedby="upload-limit"
                />
              </label>
            </div>
            <p className="upload-limit" id="upload-limit">
              <FileCode2 size={15} aria-hidden="true" />
              {text.maxSize}:{' '}
              {new Intl.NumberFormat('bg-BG', { maximumFractionDigits: 2 }).format(
                options.data.maxSourceBytes / 1024,
              )}{' '}
              KB · UTF-8 · .{language.extension}
            </p>
            {error != null &&
              (error instanceof ApiError && (error.status === 401 || error.status === 403) ? (
                <Failure error={error} />
              ) : (
                <p className="inline-error" role="alert">
                  {failureMessage}
                </p>
              ))}
            <div className="action-row">
              <button
                className="button"
                type="submit"
                disabled={busy || !file || (closed && !pending)}
              >
                <Upload size={17} aria-hidden="true" />
                {busy ? text.submitting : pending ? text.retry : text.submit}
              </button>
              {pending && (
                <button type="button" className="button secondary" onClick={reset} disabled={busy}>
                  {text.newAttempt}
                </button>
              )}
            </div>
          </form>
        </>
      )}
    </section>
  )
}
