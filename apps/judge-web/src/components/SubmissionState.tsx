import type { SubmissionStatus } from '../api/types'
import { submissionCopy as text } from '../submissionCopy'

export const isEvaluating = (status?: SubmissionStatus) =>
  status === 'PENDING' || status === 'RUNNING'
export const languageName = (language: string) =>
  text.languages[language as keyof typeof text.languages] ?? language
export function SubmissionState({ status }: { status: SubmissionStatus }) {
  return (
    <span className={`status submission-${status.toLowerCase()}`}>{text.statuses[status]}</span>
  )
}
