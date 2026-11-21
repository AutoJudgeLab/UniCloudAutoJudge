import type { components } from './schema'

// The API's record fields are present, though OpenAPI does not mark every response field required.
export type User = Omit<Required<components['schemas']['UserResponse']>, 'email'> & {
  email: string | null
}
export type Assignment = Required<components['schemas']['AssignmentResponse']>
export type AssignmentPage = Omit<
  Required<components['schemas']['AssignmentPageResponse']>,
  'content'
> & { content: Assignment[] }
export type AssignmentStatus = Assignment['status']
export type Language = Required<components['schemas']['LanguageOption']>
export type SubmissionOptions = Omit<
  Required<components['schemas']['SubmissionOptionsResponse']>,
  'languages'
> & { languages: Language[] }
export type SubmissionCreated = Required<components['schemas']['SubmissionCreatedResponse']>
export type Submission = Omit<
  Required<components['schemas']['SubmissionResponse']>,
  'sourceSize' | 'startedAt' | 'completedAt'
> & { sourceSize: number | null; startedAt: string | null; completedAt: string | null }
export type SubmissionPage = Omit<
  Required<components['schemas']['SubmissionPageResponse']>,
  'content'
> & { content: Submission[] }
export type SubmissionStatus = Submission['status']
export type TestResult = Omit<
  Required<components['schemas']['TestResultResponse']>,
  'inputData' | 'expectedOutput' | 'actualOutput' | 'executionTimeMs'
> & {
  inputData: string | null
  expectedOutput: string | null
  actualOutput: string | null
  executionTimeMs: number | null
}
type EvaluationFields = Required<components['schemas']['EvaluationResponse']>
type Unavailable =
  | 'verdict'
  | 'totalScore'
  | 'maximumScore'
  | 'passedTests'
  | 'totalTests'
  | 'executionTimeMs'
  | 'completedAt'
export type Evaluation = Omit<EvaluationFields, Unavailable | 'testResults'> & {
  [K in Unavailable]: EvaluationFields[K] | null
} & { testResults: TestResult[] | null }
export type Leaderboard = Omit<Required<components['schemas']['LeaderboardPage']>, 'content'> & {
  content: Required<components['schemas']['LeaderboardEntry']>[]
}

export type TestCase = Required<components['schemas']['TestCaseResponse']>
export type UserPage = Omit<Required<components['schemas']['UserPageResponse']>, 'content'> & {
  content: User[]
}
