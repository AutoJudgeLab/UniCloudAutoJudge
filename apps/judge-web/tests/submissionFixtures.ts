import { expect, type Page } from '@playwright/test'
export const source = 'print(sum(map(int, input().split())))'
export const upload = { name: 'main.py', mimeType: 'text/plain', buffer: Buffer.from(source) }
export const assignment = {
  id: 'a1',
  title: 'Сумиране на числа',
  description: 'Съберете две числа.',
  maximumScore: 100,
  deadline: '2030-12-01T18:00:00Z',
  status: 'PUBLISHED',
  teacherId: 't1',
  createdAt: '2026-11-01T10:00:00Z',
  updatedAt: '2026-11-01T10:00:00Z',
}
const submission = {
  submissionId: 's1',
  assignmentId: 'a1',
  assignmentRevisionId: 'r1',
  studentId: 'u1',
  programmingLanguage: 'PYTHON',
  originalFilename: 'main.py',
  sourceSize: source.length,
  status: 'PENDING',
  submittedAt: '2026-11-01T10:00:00Z',
  startedAt: null,
  completedAt: null,
  attemptCount: 0,
}

export async function setup(page: Page) {
  const state = {
    role: 'STUDENT',
    deadline: assignment.deadline,
    failNext: false,
    uploadStatus: 201,
    resultStatus: 200,
    phases: ['COMPLETED'],
    verdict: 'ACCEPTED' as string | null,
    resultCalls: 0,
    csrfCalls: 0,
    queries: [] as URLSearchParams[],
    posts: [] as {
      key: string
      csrf: string
      name: string
      language: string
      source: string
      parts: string[]
    }[],
  }
  await page.route('http://localhost:5173/api/**', async (route) => {
    const req = route.request()
    const url = new URL(req.url())
    if (url.pathname === '/api/users/me')
      return route.fulfill({
        json: {
          id: 'u1',
          username: 'student-one',
          email: 'student@example.test',
          role: state.role,
          enabled: true,
          createdAt: '2026-11-01T10:00:00Z',
        },
      })
    if (url.pathname.endsWith('/test-cases')) return route.fulfill({ json: [] })
    if (url.pathname === '/api/submissions/options')
      return route.fulfill({
        json: {
          maxSourceBytes: 128,
          languages: [
            { value: 'PYTHON', extension: 'py' },
            { value: 'JAVA', extension: 'java' },
          ],
        },
      })
    if (url.pathname === '/api/assignments/a1')
      return route.fulfill({ json: { ...assignment, deadline: state.deadline } })
    if (url.pathname === '/api/assignments/a1/submissions' && req.method() === 'POST') {
      const form = await new Response(new Blob([Uint8Array.from(req.postDataBuffer()!)]), {
        headers: { 'content-type': req.headers()['content-type'] },
      }).formData()
      const file = form.get('file') as File
      state.posts.push({
        key: req.headers()['idempotency-key'],
        csrf: req.headers()['x-csrf-token'],
        name: file.name,
        language: String(form.get('programmingLanguage')),
        source: await file.text(),
        parts: [...form.keys()].sort(),
      })
      if (state.failNext) {
        state.failNext = false
        return route.abort('failed')
      }
      if (state.uploadStatus !== 201)
        return route.fulfill({ status: state.uploadStatus, json: { code: 'DEADLINE_PASSED' } })
      return route.fulfill({
        status: 201,
        json: {
          submissionId: 's1',
          assignmentId: 'a1',
          status: 'PENDING',
          submittedAt: submission.submittedAt,
        },
      })
    }
    if (
      url.pathname === '/api/users/me/submissions' ||
      url.pathname === '/api/assignments/a1/submissions'
    ) {
      state.queries.push(url.searchParams)
      const pageNumber = Number(url.searchParams.get('page') ?? 0)
      return route.fulfill({
        json: {
          content: [
            {
              ...submission,
              originalFilename: pageNumber ? 'second.py' : 'main.py',
              status: url.searchParams.get('status') || 'PENDING',
            },
          ],
          page: pageNumber,
          size: 12,
          totalElements: 13,
          totalPages: 2,
        },
      })
    }
    if (url.pathname === '/api/submissions/s1') return route.fulfill({ json: submission })
    if (url.pathname === '/api/submissions/s1/result') {
      const phase = state.phases[Math.min(state.resultCalls++, state.phases.length - 1)]
      if (state.resultStatus !== 200) return route.fulfill({ status: state.resultStatus, json: {} })
      const complete = phase === 'COMPLETED' && state.verdict !== null
      const compile = state.verdict === 'COMPILATION_ERROR'
      return route.fulfill({
        json: {
          submissionId: 's1',
          status: phase,
          verdict: complete ? state.verdict : null,
          totalScore: complete ? (compile ? 0 : 100) : null,
          maximumScore: complete ? 100 : null,
          passedTests: complete ? (compile ? 0 : 2) : null,
          totalTests: complete ? 2 : null,
          executionTimeMs: complete ? 150 : null,
          submittedAt: submission.submittedAt,
          completedAt: complete ? '2026-11-01T10:00:03Z' : null,
          testResults:
            complete && !compile
              ? [
                  {
                    testCaseId: 't1',
                    status: 'ACCEPTED',
                    awardedPoints: 40,
                    executionTimeMs: 70,
                    inputData: '2 3',
                    expectedOutput: '5',
                    actualOutput: '5',
                  },
                  {
                    testCaseId: 't2',
                    status: 'ACCEPTED',
                    awardedPoints: 60,
                    executionTimeMs: 80,
                    inputData: null,
                    expectedOutput: null,
                    actualOutput: null,
                  },
                ]
              : complete
                ? []
                : null,
        },
      })
    }
    if (url.pathname === '/api/assignments/a1/leaderboard')
      return route.fulfill({
        json: {
          content: [
            {
              rank: 21,
              username: 'student-other',
              bestScore: 95.5,
              submissionId: 'private-submission',
            },
          ],
          page: 1,
          size: 20,
          totalElements: 21,
          totalPages: 2,
        },
      })
    return route.fulfill({ status: 404, json: {} })
  })
  await page.route('**/auth/csrf', (route) =>
    route.fulfill({ json: { headerName: 'X-CSRF-TOKEN', token: `csrf-${++state.csrfCalls}` } }),
  )
  return state
}

export async function chooseFile(page: Page) {
  await page.goto('/assignments/a1')
  await expect(page.getByRole('heading', { name: 'Предайте решение' })).toBeVisible()
  await page.getByLabel('Файл с решение', { exact: true }).setInputFiles(upload)
}
