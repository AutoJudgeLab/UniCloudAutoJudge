import { test, expect } from '@playwright/test'
import { management, publicTest } from './managementFixtures'

test('teacher creates draft with exact decimal points, UTC deadline and CSRF', async ({ page }) => {
  const state = await management(page)
  await page.goto('/assignments/new')
  await page.getByLabel('Заглавие', { exact: true }).fill('Нова задача')
  await page.getByLabel('Условие', { exact: true }).fill('Изведете сбор.')
  await page.getByLabel('Максимум точки', { exact: true }).fill('100,1250')
  await page.getByLabel('Краен срок', { exact: true }).fill('2030-12-01T18:00')
  await page.getByRole('button', { name: 'Създай чернова' }).dblclick()
  await expect(page).toHaveURL('/assignments/a1/manage')
  expect(state.writes).toHaveLength(1)
  expect(state.writes[0]).toMatchObject({
    method: 'POST',
    csrf: 'csrf-1',
    body: { title: 'Нова задача', maximumScore: '100.1250' },
  })
  expect(Object.keys(state.writes[0].body).sort()).toEqual([
    'deadline',
    'description',
    'maximumScore',
    'title',
  ])
  expect(state.writes[0].body.deadline).toMatch(/Z$/)
  await page.screenshot({ path: test.info().outputPath('editor.png'), fullPage: true })
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true)
})

test('publication failure is actionable and published metadata needs revision confirmation', async ({
  page,
}) => {
  const state = await management(page)
  state.error = 'TEST_CASE_SCORE_MISMATCH'
  await page.goto('/assignments/a1/manage')
  page.once('dialog', (dialog) => dialog.accept())
  await page.getByRole('button', { name: 'Публикувай', exact: true }).click()
  await expect(page.getByRole('alert')).toContainText('Сборът от точките')
  state.error = ''
  state.assignment.status = 'PUBLISHED'
  await page.reload()
  await page.getByLabel('Заглавие', { exact: true }).fill('Нова ревизия')
  page.once('dialog', (dialog) => dialog.dismiss())
  await page.getByRole('button', { name: 'Запази промените' }).click()
  expect(state.writes).toHaveLength(1)
  page.once('dialog', (dialog) => dialog.accept())
  await page.getByRole('button', { name: 'Запази промените' }).click()
  await expect(page.locator('.page-heading')).toContainText('Чернова')
  expect(state.writes[1].path).toBe('/api/assignments/a1?createNewRevision=true')
})

test('test edits fork a draft and reload copied IDs before the next edit', async ({ page }) => {
  const state = await management(page)
  state.assignment.status = 'PUBLISHED'
  state.tests = [publicTest]
  await page.goto('/assignments/a1/manage')
  await page.getByText('Тест 1', { exact: true }).click()
  await page.getByRole('button', { name: 'Редактирай теста' }).click()
  await page.getByLabel('Очакван изход', { exact: true }).fill('6\n')
  page.once('dialog', (dialog) => dialog.accept())
  await page.getByRole('button', { name: 'Запази теста' }).click()
  await expect(page.locator('.page-heading')).toContainText('Чернова')
  await expect(page.getByText('Тестът е запазен.', { exact: false })).toBeVisible()
  const copiedId = state.tests[0].id
  expect(copiedId).not.toBe('t1')
  await page.getByText('Тест 1', { exact: true }).click()
  await page.getByRole('button', { name: 'Редактирай теста' }).click()
  await page.getByLabel('Очакван изход', { exact: true }).fill('7\n')
  await page.getByRole('button', { name: 'Запази теста' }).click()
  await expect(page.getByText('Тестът е запазен.', { exact: false })).toBeVisible()
  expect(state.writes[1].path).toBe('/api/assignments/a1/test-cases/' + copiedId)
  expect(state.writes.map((write) => write.csrf)).toEqual(['csrf-1', 'csrf-2'])
})

test('test form preserves blank strings and whitespace, hidden is the default', async ({
  page,
}) => {
  const state = await management(page)
  await page.goto('/assignments/a1/manage')
  await page.getByRole('button', { name: 'Добави тест' }).click()
  await page.getByLabel('Очакван изход', { exact: true }).fill('  text\n')
  await page.getByRole('button', { name: 'Запази теста' }).click()
  await expect(page.getByText('Тестът е запазен.', { exact: false })).toBeVisible()
  expect(state.writes[0].body).toEqual({
    inputData: '',
    expectedOutput: '  text\n',
    points: '1',
    executionOrder: 0,
    isHidden: true,
  })
})

test('archive becomes read-only and history-protected deletion is explained', async ({ page }) => {
  const state = await management(page)
  state.error = 'GRADING_HISTORY_RETAINED'
  await page.goto('/assignments/a1/manage')
  page.once('dialog', (dialog) => dialog.accept())
  await page.getByRole('button', { name: 'Изтрий заданието' }).click()
  await expect(page.getByRole('alert')).toContainText('запазена история')
  state.error = ''
  page.once('dialog', (dialog) => dialog.accept())
  await page.getByRole('button', { name: 'Архивирай', exact: true }).click()
  await expect(page.locator('.page-heading')).toContainText('Архивирано')
  await expect(page.getByRole('button', { name: /Запази|Добави|Изтрий|Публикувай/ })).toHaveCount(0)
})

test('unconfirmed creation requires explicit retry confirmation', async ({ page }) => {
  const state = await management(page)
  state.failNext = true
  await page.goto('/assignments/new')
  await page.getByLabel('Заглавие', { exact: true }).fill('Задача')
  await page.getByLabel('Условие', { exact: true }).fill('Условие')
  await page.getByLabel('Краен срок', { exact: true }).fill('2030-12-01T18:00')
  await page.getByRole('button', { name: 'Създай чернова' }).click()
  await expect(page.getByRole('alert')).toContainText('Няма потвърждение')
  page.once('dialog', (dialog) => dialog.dismiss())
  await page.getByRole('button', { name: 'Създай чернова' }).click()
  expect(state.writes).toHaveLength(1)
  await expect(page.getByLabel('Заглавие', { exact: true })).toHaveValue('Задача')
})

test('students see public examples but cannot open management or admin routes', async ({
  page,
}) => {
  const state = await management(page, 'STUDENT')
  state.assignment.status = 'PUBLISHED'
  state.tests = [
    publicTest,
    { ...publicTest, id: 'secret', isHidden: true, inputData: 'hidden-marker' },
  ]
  await page.goto('/assignments/a1')
  await page.getByText('Тест 1', { exact: true }).click()
  await expect(page.locator('.test-values')).toContainText('2 3')
  await expect(page.getByText('hidden-marker')).toHaveCount(0)
  for (const path of ['/assignments/new', '/assignments/a1/manage', '/admin/users']) {
    await page.goto(path)
    await expect(page.getByRole('alert')).toContainText('Нямате достъп')
  }
  expect(state.writes).toHaveLength(0)
})

test('teacher cannot manage another teacher assignment even if fetched', async ({ page }) => {
  const state = await management(page)
  state.assignment.teacherId = 'another-teacher'
  await page.goto('/assignments/a1/manage')
  await expect(page.getByRole('alert')).toContainText('Нямате достъп')
  await expect(page.getByLabel('Заглавие', { exact: true })).toHaveCount(0)
})

test('admin search combines filters and preserves them during pagination', async ({ page }) => {
  const state = await management(page, 'ADMIN')
  await page.goto('/admin/users')
  await page.getByLabel('Имейл', { exact: true }).fill('example.test')
  await page.getByRole('button', { name: 'Търси' }).click()
  await expect(page).toHaveURL(/email=example.test/)
  await expect.poll(() => state.queries.at(-1)?.get('email')).toBe('example.test')
  await page.getByRole('combobox', { name: 'Роля', exact: true }).selectOption('STUDENT')
  await page.getByRole('combobox', { name: 'Достъп', exact: true }).selectOption('true')
  await page.getByRole('button', { name: 'Следваща' }).click()
  await expect(page).toHaveURL(/page=1/)
  await expect.poll(() => state.queries.at(-1)?.get('page')).toBe('1')
  const query = state.queries.at(-1)!
  expect([query.get('email'), query.get('role'), query.get('enabled')]).toEqual([
    'example.test',
    'STUDENT',
    'true',
  ])
  await page.screenshot({ path: test.info().outputPath('users.png'), fullPage: true })
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true)
})

test('admin status changes require confirmation and never change Portal roles', async ({
  page,
}) => {
  const state = await management(page, 'ADMIN')
  await page.goto('/admin/users/s1')
  page.once('dialog', (dialog) => dialog.dismiss())
  await page.getByRole('button', { name: 'Спри достъпа' }).click()
  expect(state.writes).toHaveLength(0)
  page.once('dialog', (dialog) => dialog.accept())
  await page.getByRole('button', { name: 'Спри достъпа' }).click()
  await expect(page.getByRole('button', { name: 'Активирай достъпа' })).toBeVisible()
  expect(state.writes[0]).toMatchObject({
    method: 'PATCH',
    path: '/api/admin/users/s1/status',
    csrf: 'csrf-1',
    body: { enabled: false },
  })
  await expect(page.getByRole('combobox')).toHaveCount(0)
  await page.goto('/admin/users/u1')
  await expect(page.getByRole('button', { name: 'Спри достъпа' })).toHaveCount(0)
})
