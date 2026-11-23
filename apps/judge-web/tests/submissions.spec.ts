import { test, expect } from '@playwright/test'
import { setup, chooseFile, upload, source, assignment } from './submissionFixtures'

test('upload sends exactly two parts, fresh CSRF and one idempotent request', async ({ page }) => {
  const state = await setup(page)
  await chooseFile(page)
  await page.screenshot({ path: test.info().outputPath('upload.png'), fullPage: true })
  await page.getByRole('button', { name: 'Предай решение', exact: true }).dblclick()
  await expect(page).toHaveURL('/submissions/s1')
  expect(state.posts).toHaveLength(1)
  expect(state.posts[0]).toMatchObject({
    name: 'main.py',
    source,
    language: 'PYTHON',
    csrf: 'csrf-1',
    parts: ['file', 'programmingLanguage'],
  })
  expect(state.posts[0].key).toMatch(/^[a-z0-9-]{36}$/)
  expect(await page.evaluate(() => sessionStorage.length)).toBe(0)
})

test('uncertain uploads retain key and exact content after reload and deadline', async ({
  page,
}) => {
  const state = await setup(page)
  state.failNext = true
  await chooseFile(page)
  await page.getByRole('button', { name: 'Предай решение', exact: true }).click()
  await expect(page.getByRole('alert')).toContainText('не е потвърдено')
  expect(state.posts).toHaveLength(1)
  const key = state.posts[0].key
  expect(await page.evaluate(() => JSON.stringify(sessionStorage))).not.toContain(source)
  state.deadline = '2020-01-01T00:00:00Z'
  await page.reload()
  await expect(page.getByRole('status').filter({ hasText: 'непотвърдено предаване' })).toBeVisible()
  await page
    .getByLabel('Файл с решение', { exact: true })
    .setInputFiles({ ...upload, buffer: Buffer.from('print(99)') })
  await page.getByRole('button', { name: 'Повтори предаването' }).click()
  await expect(page.getByRole('alert')).toContainText('не е същият файл')
  expect(state.posts).toHaveLength(1)
  await page.getByLabel('Файл с решение', { exact: true }).setInputFiles(upload)
  await page.getByRole('button', { name: 'Повтори предаването' }).click()
  await expect(page).toHaveURL('/submissions/s1')
  expect(state.posts).toHaveLength(2)
  expect(state.posts[1]).toMatchObject({
    key,
    source,
    name: 'main.py',
    language: 'PYTHON',
    csrf: 'csrf-2',
  })
})

test('file validation follows API size and language options', async ({ page }) => {
  const state = await setup(page)
  await chooseFile(page)
  await expect(
    page.getByRole('combobox', { name: 'Език', exact: true }).getByRole('option'),
  ).toHaveCount(2)
  for (const [file, message] of [
    [{ ...upload, buffer: Buffer.alloc(0) }, 'непразен UTF-8'],
    [{ ...upload, buffer: Buffer.alloc(129, 65) }, 'надвишава'],
    [{ ...upload, name: 'Main.java' }, 'разширението'],
    [{ ...upload, buffer: Buffer.from([255, 254]) }, 'непразен UTF-8'],
  ] as const) {
    await page.getByLabel('Файл с решение', { exact: true }).setInputFiles(file)
    await page.getByRole('button', { name: 'Предай решение', exact: true }).click()
    await expect(page.getByRole('alert')).toContainText(message)
  }
  expect(state.posts).toHaveLength(0)
})

test('the server deadline remains authoritative', async ({ page }) => {
  const state = await setup(page)
  state.uploadStatus = 409
  await chooseFile(page)
  await page.getByRole('button', { name: 'Предай решение', exact: true }).click()
  await expect(page.getByRole('alert')).toContainText('преди приемането')
  await expect(page).toHaveURL('/assignments/a1')
})

test('history filters and pagination retain the selected scope', async ({ page }) => {
  const state = await setup(page)
  await page.goto('/submissions')
  await page.getByRole('combobox', { name: 'Състояние' }).selectOption('COMPLETED')
  await page.getByRole('combobox', { name: 'Език' }).selectOption('PYTHON')
  await page.getByRole('button', { name: 'Следваща' }).click()
  await expect(page.getByRole('heading', { name: 'second.py' })).toBeVisible()
  const query = state.queries.at(-1)!
  expect(query.get('page')).toBe('1')
  expect(query.get('status')).toBe('COMPLETED')
  expect(query.get('programmingLanguage')).toBe('PYTHON')
  await page.screenshot({ path: test.info().outputPath('history.png'), fullPage: true })
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true)
})

test('results progress through pending and running and stop polling at completion', async ({
  page,
}) => {
  const state = await setup(page)
  state.phases = ['PENDING', 'RUNNING', 'COMPLETED']
  const pageErrors: string[] = []
  page.on('pageerror', (error) => pageErrors.push(error.message))
  await page.clock.install()
  await page.goto('/submissions/s1')
  await expect(page.getByRole('status').filter({ hasText: 'изчаква оценяване' })).toBeVisible()
  await expect(page.locator('.result-summary')).toContainText('Оценка все още не е налична')
  await page.clock.runFor(3100)
  await expect(page.getByRole('status').filter({ hasText: 'се проверява' })).toBeVisible()
  await page.clock.runFor(3100)
  await expect(page.getByRole('heading', { name: 'Правилно решение' })).toBeVisible()
  const calls = state.resultCalls
  await page.clock.runFor(10000)
  expect(state.resultCalls).toBe(calls)
  expect(pageErrors).toEqual([])
  await page.getByText('Тест 2', { exact: true }).click()
  await expect(page.getByText('Данните за този тест не са достъпни.')).toBeVisible()
  await page.screenshot({ path: test.info().outputPath('result.png'), fullPage: true })
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true)
})

for (const phase of ['FAILED', 'CANCELLED', 'COMPLETED']) {
  test(`${phase} with unavailable evaluation does not fabricate a zero grade`, async ({ page }) => {
    const state = await setup(page)
    state.phases = [phase]
    state.verdict = null
    await page.clock.install()
    await page.goto('/submissions/s1')
    await expect(page.locator('.result-metrics strong')).toHaveText(['—', '—', '—'])
    const calls = state.resultCalls
    await page.clock.runFor(10000)
    expect(state.resultCalls).toBe(calls)
  })
}

test('compilation failure shows the real zero grade without invented diagnostics', async ({
  page,
}) => {
  const state = await setup(page)
  state.verdict = 'COMPILATION_ERROR'
  await page.goto('/submissions/s1')
  await expect(page.getByRole('heading', { name: 'Грешка при компилиране' })).toBeVisible()
  await expect(page.locator('.result-metrics strong').first()).toHaveText('0 / 100')
  await expect(page.getByRole('status')).toContainText('Тестовете не са изпълнени')
})

test('polling outage pauses updates and manual retry recovers', async ({ page }) => {
  const state = await setup(page)
  state.phases = ['PENDING']
  await page.goto('/submissions/s1')
  await expect(page.getByRole('status').filter({ hasText: 'изчаква оценяване' })).toBeVisible()
  await expect(page.locator('.result-summary')).toBeVisible()
  state.resultStatus = 503
  await page.getByRole('button', { name: 'Провери отново' }).click()
  await expect(page.getByText('Обновяването е прекъснато.', { exact: false })).toBeVisible()
  state.resultStatus = 200
  state.phases = ['COMPLETED']
  await page.getByRole('button', { name: 'Провери отново' }).click()
  await expect(page.getByRole('heading', { name: 'Правилно решение' })).toBeVisible()
})

test('leaderboard uses server ranks without links to private submissions', async ({ page }) => {
  await setup(page)
  await page.goto('/assignments/a1/leaderboard?page=1')
  await expect(page.getByRole('cell', { name: '21', exact: true })).toBeVisible()
  await expect(page.getByRole('rowheader', { name: 'student-other' })).toBeVisible()
  expect(
    await page.locator('a').evaluateAll((links) => links.map((link) => link.getAttribute('href'))),
  ).not.toContain('/submissions/private-submission')
})

test('teachers can inspect assignment history but cannot upload as a student', async ({ page }) => {
  const state = await setup(page)
  state.role = 'TEACHER'
  await page.goto('/assignments/a1')
  await expect(page.getByRole('heading', { name: assignment.title })).toBeVisible()
  await expect(page.getByLabel('Файл с решение')).toHaveCount(0)
  await page.getByRole('link', { name: 'История на предаванията' }).click()
  await expect(page).toHaveURL('/assignments/a1/submissions')
  await expect(page.getByRole('heading', { name: 'main.py' })).toBeVisible()
})
