import { test, expect, type Page } from '@playwright/test'

const assignment = {
  id: 'a1',
  title: 'Сумиране на числа',
  description: 'Прочетете две цели числа и изведете техния сбор.',
  maximumScore: 100,
  deadline: '2030-12-01T18:00:00Z',
  status: 'PUBLISHED',
  teacherId: 't1',
  createdAt: '2026-11-01T10:00:00Z',
  updatedAt: '2026-11-01T10:00:00Z',
}
const user = (role = 'STUDENT') => ({
  id: 'u1',
  username: 'test-student',
  email: 'student@example.test',
  role,
  enabled: true,
  createdAt: '2026-11-01T10:00:00Z',
})

async function mockApi(page: Page, role: string | null = 'STUDENT') {
  const state = { role, listStatus: 200, logoutStatus: 204, csrfHeader: '', csrfCalls: 0 }
  await page.route('http://localhost:5173/api/**', async (route) => {
    const url = new URL(route.request().url())
    if (url.pathname.endsWith('/test-cases')) return route.fulfill({ json: [] })
    if (url.pathname === '/api/submissions/options')
      return route.fulfill({
        json: { maxSourceBytes: 1048576, languages: [{ value: 'PYTHON', extension: 'py' }] },
      })
    if (url.pathname === '/api/users/me')
      return route.fulfill({
        status: state.role ? 200 : 401,
        json: state.role ? user(state.role) : {},
      })
    if (url.pathname === '/api/assignments') {
      if (state.listStatus !== 200) return route.fulfill({ status: state.listStatus, json: {} })
      const pageNumber = Number(url.searchParams.get('page') ?? 0)
      const status = url.searchParams.get('status') || 'PUBLISHED'
      return route.fulfill({
        json: {
          content: [
            {
              ...assignment,
              id: `a${pageNumber + 1}`,
              status,
              title: pageNumber ? 'Работа с масиви' : assignment.title,
            },
          ],
          page: pageNumber,
          size: 12,
          totalElements: 13,
          totalPages: 2,
        },
      })
    }
    if (url.pathname === '/api/assignments/a1') return route.fulfill({ json: assignment })
    return route.fulfill({ status: 404, json: {} })
  })
  await page.route('**/auth/csrf', async (route) => {
    state.csrfCalls++
    return route.fulfill({ json: { headerName: 'X-CSRF-TOKEN', token: 'fresh-session-csrf' } })
  })
  await page.route('**/auth/logout', async (route) => {
    state.csrfHeader = route.request().headers()['x-csrf-token'] ?? ''
    if (state.logoutStatus === 204) state.role = null
    return route.fulfill({ status: state.logoutStatus, body: '' })
  })
  return state
}

test('unauthenticated visitors use Portal entry point without browser token storage', async ({
  page,
}) => {
  const state = await mockApi(page, null)
  await page.route('**/oauth2/authorization/portal', async (route) => {
    state.role = 'STUDENT'
    await route.fulfill({ status: 302, headers: { location: '/assignments' } })
  })
  await page.goto('/login')
  await expect(page.getByRole('heading', { name: 'Добре дошли в Auto Judge' })).toBeVisible()
  await expect(page.getByRole('link', { name: 'Вход през UniCloud' })).toHaveAttribute(
    'href',
    '/oauth2/authorization/portal',
  )
  await page.screenshot({ path: test.info().outputPath('login.png'), fullPage: true })
  await page.getByRole('link', { name: 'Вход през UniCloud' }).click()
  await expect(page.getByRole('heading', { name: 'Задания', exact: true })).toBeVisible()
  expect(await page.evaluate(() => [localStorage.length, sessionStorage.length])).toEqual([0, 0])
})

for (const [role, label] of [
  ['STUDENT', 'Студент'],
  ['TEACHER', 'Преподавател'],
  ['ADMIN', 'Администратор'],
]) {
  test(`${role} sees real role context, assignment details and profile`, async ({ page }) => {
    await mockApi(page, role)
    await page.goto('/assignments')
    await expect(page.getByRole('heading', { name: assignment.title })).toBeVisible()
    await expect(page.locator('.topbar')).toContainText(label)
    if (role === 'STUDENT')
      await expect(page.getByRole('combobox', { name: 'Статус', exact: true })).toHaveCount(0)
    else await expect(page.getByRole('combobox', { name: 'Статус', exact: true })).toBeVisible()
    await expect(page.getByRole('button', { name: /Създай|Публикувай|Предай/ })).toHaveCount(0)
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true)
    if (role === 'ADMIN')
      await page.screenshot({ path: test.info().outputPath('assignments.png'), fullPage: true })
    await page.getByRole('link', { name: assignment.title, exact: true }).click()
    await expect(page.getByRole('heading', { name: 'Условие' })).toBeVisible()
    await expect(page.locator('.statement')).toHaveText(assignment.description)
    await page.getByRole('link', { name: 'Моят профил' }).click()
    await expect(
      page.getByRole('definition').filter({ hasText: 'student@example.test' }),
    ).toBeVisible()
    await page.reload()
    await expect(page.getByRole('heading', { name: 'Вашият профил' })).toBeVisible()
  })
}

test('filters and pagination are reflected in API queries and browser history', async ({
  page,
}) => {
  await mockApi(page, 'TEACHER')
  await page.goto('/assignments')
  await page.getByRole('combobox', { name: 'Статус', exact: true }).selectOption('DRAFT')
  await expect(page.locator('.assignment-list')).toContainText('Чернова')
  await page.getByRole('combobox', { name: 'Подредба', exact: true }).selectOption('deadline,asc')
  const request = page.waitForRequest(
    (req) =>
      req.url().includes('/api/assignments?') &&
      new URL(req.url()).searchParams.get('page') === '1',
  )
  await page.getByRole('button', { name: 'Следваща' }).click()
  const query = new URL((await request).url()).searchParams
  expect(query.get('status')).toBe('DRAFT')
  expect(query.get('sort')).toBe('deadline,asc')
  await expect(page.getByRole('heading', { name: 'Работа с масиви' })).toBeVisible()
  await page.goBack()
  await expect(page.getByRole('heading', { name: assignment.title })).toBeVisible()
})

test('logout fetches fresh CSRF, clears cached identity and protects back navigation', async ({
  page,
}) => {
  const state = await mockApi(page)
  await page.goto('/assignments')
  await expect(page.getByRole('heading', { name: assignment.title })).toBeVisible()
  await page.getByRole('button', { name: 'Изход', exact: true }).click()
  await expect(page.getByRole('link', { name: 'Вход през UniCloud' })).toBeVisible()
  expect(state.csrfCalls).toBe(1)
  expect(state.csrfHeader).toBe('fresh-session-csrf')
  await page.goto('/assignments')
  await expect(page).toHaveURL(new RegExp('/login'))
  await expect(page.getByRole('heading', { name: assignment.title })).toHaveCount(0)
})

test('failed logout does not pretend to end the session', async ({ page }) => {
  const state = await mockApi(page)
  state.logoutStatus = 403
  await page.goto('/assignments')
  await page.getByRole('button', { name: 'Изход', exact: true }).click()
  await expect(page.getByRole('alert')).toContainText('Изходът не е потвърден')
  await expect(page.getByRole('heading', { name: assignment.title })).toBeVisible()
})

test('session expiry during a protected request returns to login without stale data', async ({
  page,
}) => {
  const state = await mockApi(page)
  await page.goto('/assignments')
  await expect(page.getByRole('heading', { name: assignment.title })).toBeVisible()
  state.role = null
  state.listStatus = 401
  await page.getByRole('button', { name: 'Следваща' }).click()
  await expect(page).toHaveURL(new RegExp('/login'))
  await expect(page.getByRole('heading', { name: assignment.title })).toHaveCount(0)
})

test('unavailable identity provider offers retry without automatic SSO loops', async ({ page }) => {
  await page.route('**/api/users/me', (route) =>
    route.fulfill({ status: 503, json: { code: 'IDENTITY_PROVIDER_UNAVAILABLE' } }),
  )
  await page.goto('/assignments')
  await expect(page.getByRole('alert')).toContainText('временно е недостъпна')
  await expect(page.getByRole('button', { name: 'Опитай отново' })).toBeVisible()
  await expect(page).toHaveURL('/assignments')
})

test('hidden assignments return a safe unavailable page', async ({ page }) => {
  await mockApi(page)
  await page.goto('/assignments/not-visible')
  await expect(page.getByRole('alert')).toContainText('не е намерена или нямате достъп')
  await expect(page.getByRole('link', { name: 'Към заданията' })).toBeVisible()
})
