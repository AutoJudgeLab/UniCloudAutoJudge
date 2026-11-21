import type { Page } from '@playwright/test'
import { assignment } from './submissionFixtures'
import type { TestCase } from '../src/api/types'

export async function management(page: Page, role = 'TEACHER') {
  const state = {
    role,
    assignment: { ...assignment, status: 'DRAFT', teacherId: 'u1' },
    tests: [] as TestCase[],
    writes: [] as { path: string; method: string; body: Record<string, unknown>; csrf: string }[],
    queries: [] as URLSearchParams[],
    csrfCalls: 0,
    clock: 0,
    error: '' as string,
    failNext: false,
    userEnabled: true,
  }
  const actor = {
    id: 'u1',
    username: 'teacher-one',
    email: 'teacher@example.test',
    role,
    enabled: true,
    createdAt: assignment.createdAt,
  }
  function updateRevision() {
    state.assignment.status = 'DRAFT'
    state.assignment.updatedAt = new Date(
      Date.parse(assignment.updatedAt) + ++state.clock * 1000,
    ).toISOString()
    state.tests = state.tests.map((test) => ({
      ...test,
      id: 'r' + state.clock + '-' + test.executionOrder,
    }))
  }
  await page.route('**/auth/csrf', (route) =>
    route.fulfill({ json: { headerName: 'X-CSRF-TOKEN', token: 'csrf-' + ++state.csrfCalls } }),
  )
  await page.route('http://localhost:5173/api/**', async (route) => {
    const req = route.request(),
      url = new URL(req.url()),
      path = url.pathname
    if (req.method() !== 'GET') {
      state.writes.push({
        path: path + url.search,
        method: req.method(),
        body: req.postData() ? req.postDataJSON() : {},
        csrf: req.headers()['x-csrf-token'],
      })
      if (state.failNext) {
        state.failNext = false
        return route.abort('failed')
      }
      if (state.error) return route.fulfill({ status: 409, json: { code: state.error } })
    }
    if (path === '/api/users/me') return route.fulfill({ json: { ...actor, role: state.role } })
    if (path === '/api/assignments' && req.method() === 'POST') {
      Object.assign(state.assignment, req.postDataJSON())
      return route.fulfill({ status: 201, json: state.assignment })
    }
    if (path === '/api/assignments')
      return route.fulfill({
        json: { content: [state.assignment], page: 0, size: 12, totalElements: 1, totalPages: 1 },
      })
    if (path === '/api/assignments/a1') {
      if (req.method() === 'DELETE') return route.fulfill({ status: 204 })
      if (req.method() === 'PUT') {
        Object.assign(state.assignment, req.postDataJSON())
        updateRevision()
      }
      return route.fulfill({ json: state.assignment })
    }
    if (path.endsWith('/publish')) {
      state.assignment.status = 'PUBLISHED'
      return route.fulfill({ json: state.assignment })
    }
    if (path.endsWith('/archive')) {
      state.assignment.status = 'ARCHIVED'
      return route.fulfill({ json: state.assignment })
    }
    if (path === '/api/assignments/a1/test-cases') {
      if (req.method() === 'POST') {
        if (state.assignment.status === 'PUBLISHED') updateRevision()
        const test = { ...req.postDataJSON(), assignmentId: 'a1', id: 'new-' + state.tests.length }
        state.tests.push(test)
        return route.fulfill({ status: 201, json: test })
      }
      return route.fulfill({
        json: state.role === 'STUDENT' ? state.tests.filter((test) => !test.isHidden) : state.tests,
      })
    }
    if (path.startsWith('/api/assignments/a1/test-cases/')) {
      const index = state.tests.findIndex((test) => test.id === path.split('/').at(-1))
      if (index < 0) return route.fulfill({ status: 404, json: { code: 'TEST_CASE_NOT_FOUND' } })
      if (state.assignment.status === 'PUBLISHED') updateRevision()
      if (req.method() === 'DELETE') {
        state.tests.splice(index, 1)
        return route.fulfill({ status: 204 })
      }
      Object.assign(state.tests[index], req.postDataJSON())
      return route.fulfill({ json: state.tests[index] })
    }
    if (path === '/api/admin/users') {
      state.queries.push(url.searchParams)
      return route.fulfill({
        json: {
          content: [
            {
              ...actor,
              id: 's1',
              username: 'student-one',
              role: 'STUDENT',
              enabled: state.userEnabled,
            },
          ],
          page: Number(url.searchParams.get('page') ?? 0),
          size: 12,
          totalPages: 2,
          totalElements: 13,
        },
      })
    }
    if (path === '/api/admin/users/s1/status') {
      state.userEnabled = req.postDataJSON().enabled
      return route.fulfill({
        json: {
          ...actor,
          id: 's1',
          role: 'STUDENT',
          username: 'student-one',
          enabled: state.userEnabled,
        },
      })
    }
    if (path === '/api/admin/users/s1')
      return route.fulfill({
        json: {
          ...actor,
          id: 's1',
          username: 'student-one',
          role: 'STUDENT',
          enabled: state.userEnabled,
        },
      })
    if (path === '/api/admin/users/u1') return route.fulfill({ json: actor })
    if (path === '/api/submissions/options')
      return route.fulfill({ json: { maxSourceBytes: 1000, languages: [] } })
    return route.fulfill({ status: 404, json: {} })
  })
  return state
}

export const publicTest: TestCase = {
  id: 't1',
  assignmentId: 'a1',
  inputData: '2 3\n',
  expectedOutput: '5\n',
  points: 100,
  isHidden: false,
  executionOrder: 0,
}
