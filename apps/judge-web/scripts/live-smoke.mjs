// Opt-in: creates a real assignment and submissions; uses dedicated Portal test users.
// Run from apps/judge-web. Never use production identities or enable tracing here.
import { readFile, writeFile, mkdir } from 'node:fs/promises'
import { resolve } from 'node:path'
import assert from 'node:assert/strict'
import { chromium, expect } from '@playwright/test'

const origin = process.env.JUDGE_SMOKE_ORIGIN ?? 'http://localhost:5173'
const issuer = process.env.JUDGE_SMOKE_ISSUER ?? 'https://unicloud-portal-staging.onrender.com'
const accountPath = process.env.JUDGE_SMOKE_ACCOUNTS
if (!accountPath)
  throw new Error('Set JUDGE_SMOKE_ACCOUNTS to the dedicated test-account JSON file.')
const accounts = JSON.parse(await readFile(accountPath, 'utf8'))
const output = resolve('../../.local/frontend-smoke')
await mkdir(output, { recursive: true })
const browser = await chromium.launch()
const sessions = {}
const report = { origin, startedAt: new Date().toISOString(), submissions: [] }
let step = 'start'
function progress(message) {
  step = message
  console.log(message)
}
async function login(role) {
  progress('Portal login: ' + role)
  const context = await browser.newContext({ baseURL: origin })
  const page = await context.newPage()
  page.setDefaultTimeout(20000)
  page.setDefaultNavigationTimeout(60000)
  await page.goto('/login')
  await page.getByRole('link', { name: 'Вход през UniCloud' }).click()
  await page.waitForURL((url) => url.origin === issuer && url.pathname === '/login.html', {
    timeout: 60000,
  })
  await page.locator('#email').fill(accounts[role].email)
  await page.locator('#password').fill(accounts[role].password)
  await page.locator('#submit').click()
  await page.waitForURL(origin + '/assignments', { timeout: 60000 })
  const response = await context.request.get('/api/users/me')
  assert.equal(response.status(), 200)
  const user = await response.json()
  assert.equal(user.role, role)
  assert.equal(user.email, accounts[role].email)
  const cookie = (await context.cookies(origin)).find((cookie) => cookie.name === 'JUDGE_SESSION')
  assert.ok(cookie?.httpOnly && cookie.secure)
  assert.deepEqual(await page.evaluate(() => [localStorage.length, sessionStorage.length]), [0, 0])
  sessions[role] = { context, page, user }
  return page
}
try {
  const teacher = await login('TEACHER')
  progress('Teacher creates assignment and public/hidden tests')
  await teacher.getByRole('link', { name: 'Ново задание', exact: true }).click()
  await teacher
    .getByLabel('Заглавие', { exact: true })
    .fill('SSO smoke — ' + new Date().toISOString())
  await teacher
    .getByLabel('Условие', { exact: true })
    .fill('Прочетете две цели числа и изведете сбора им.')
  await teacher
    .getByLabel('Краен срок', { exact: true })
    .fill(new Date(Date.now() + 7 * 86400000).toISOString().slice(0, 16))
  await teacher.getByRole('button', { name: 'Създай чернова' }).click()
  await teacher.waitForURL(/\/assignments\/[^/]+\/manage$/)
  report.assignmentId = new URL(teacher.url()).pathname.split('/')[2]
  for (const [input, expected, points, hidden] of [
    ['2 3\n', '5\n', '40', false],
    ['40 2\n', '42\n', '60', true],
  ]) {
    await teacher.getByRole('button', { name: 'Добави тест' }).click()
    await teacher.getByLabel('Входни данни', { exact: true }).fill(input)
    await teacher.getByLabel('Очакван изход', { exact: true }).fill(expected)
    await teacher.getByLabel('Точки за теста', { exact: true }).fill(points)
    await teacher.getByLabel('Скрит тест', { exact: true }).setChecked(hidden)
    await teacher.getByRole('button', { name: 'Запази теста' }).click()
    await expect(teacher.getByText('Тестът е запазен.', { exact: false })).toBeVisible()
  }
  teacher.once('dialog', (dialog) => dialog.accept())
  await teacher.getByRole('button', { name: 'Публикувай', exact: true }).click()
  await expect(teacher.locator('.page-heading')).toContainText('Публикувано')
  const student = await login('STUDENT')
  await student.bringToFront()
  const assignmentPath = '/assignments/' + report.assignmentId
  const publicCases = await sessions.STUDENT.context.request.get(
    '/api' + assignmentPath + '/test-cases',
  )
  assert.equal(publicCases.status(), 200)
  const cases = await publicCases.json()
  assert.equal(cases.length, 1)
  assert.equal(cases[0].isHidden, false)
  const variants = [
    [
      'ACCEPTED',
      'Правилно решение',
      'import java.util.Scanner; public class Main { public static void main(String[] args) { Scanner s = new Scanner(System.in); System.out.println(s.nextInt()+s.nextInt()); } }',
    ],
    [
      'WRONG_ANSWER',
      'Грешен отговор',
      'public class Main { public static void main(String[] args) { System.out.println(0); } }',
    ],
    ['COMPILATION_ERROR', 'Грешка при компилиране', 'public class Main { broken syntax }'],
  ]
  for (const [verdict, label, source] of variants) {
    progress('Student upload and real Docker grading: ' + verdict)
    await student.goto(assignmentPath)
    await student.getByRole('combobox', { name: 'Език', exact: true }).selectOption('JAVA')
    await student
      .getByLabel('Файл с решение', { exact: true })
      .setInputFiles({ name: 'Main.java', mimeType: 'text/plain', buffer: Buffer.from(source) })
    await student.getByRole('button', { name: 'Предай решение', exact: true }).click()
    await student.waitForURL(/\/submissions\/[^/]+$/)
    await expect(student.getByRole('heading', { name: label, exact: true })).toBeVisible({
      timeout: 90000,
    })
    const id = new URL(student.url()).pathname.split('/')[2]
    const response = await sessions.STUDENT.context.request.get(
      '/api/submissions/' + id + '/result',
    )
    assert.equal(response.status(), 200)
    const result = await response.json()
    assert.equal(result.verdict, verdict)
    assert.equal(result.status, 'COMPLETED')
    assert.equal(Number(result.totalScore), verdict === 'ACCEPTED' ? 100 : 0)
    if (verdict !== 'COMPILATION_ERROR') {
      assert.equal(
        result.testResults.filter(
          (test) =>
            test.inputData === null && test.expectedOutput === null && test.actualOutput === null,
        ).length,
        1,
      )
    }
    report.submissions.push({ id, verdict, score: result.totalScore })
    await student.screenshot({ path: output + '/' + verdict + '.png', fullPage: true })
  }
  progress('History, leaderboard, teacher review and authorization')
  await student.goto(assignmentPath + '/submissions')
  await expect(student.getByRole('heading', { name: 'Main.java', exact: true })).toHaveCount(3)
  await student.goto(assignmentPath + '/leaderboard')
  await expect(student.getByRole('cell', { name: '100', exact: true })).toBeVisible()
  assert.equal((await sessions.STUDENT.context.request.get('/api/admin/users')).status(), 403)
  await teacher.goto(assignmentPath + '/submissions')
  await expect(teacher.getByRole('heading', { name: 'Main.java', exact: true })).toHaveCount(3)
  const admin = await login('ADMIN')
  progress('Administrator searches and disables/re-enables dedicated student')
  await admin.getByRole('link', { name: 'Потребители', exact: true }).click()
  await admin.getByLabel('Имейл', { exact: true }).fill(accounts.STUDENT.email)
  await admin.getByRole('button', { name: 'Търси' }).click()
  await expect(admin).toHaveURL(/email=/)
  await admin.getByRole('link', { name: 'Профил', exact: true }).click()
  admin.once('dialog', (dialog) => dialog.accept())
  await admin.getByRole('button', { name: 'Спри достъпа', exact: true }).click()
  await expect(admin.getByRole('button', { name: 'Активирай достъпа', exact: true })).toBeVisible()
  try {
    assert.equal((await sessions.STUDENT.context.request.get('/api/users/me')).status(), 401)
  } finally {
    admin.once('dialog', (dialog) => dialog.accept())
    await admin.getByRole('button', { name: 'Активирай достъпа', exact: true }).click()
    await expect(admin.getByRole('button', { name: 'Спри достъпа', exact: true })).toBeVisible()
  }
  progress('Archive preserves evaluation history; logout ends the session')
  await teacher.goto(assignmentPath + '/manage')
  teacher.once('dialog', (dialog) => dialog.accept())
  await teacher.getByRole('button', { name: 'Архивирай', exact: true }).click()
  await expect(teacher.locator('.page-heading')).toContainText('Архивирано')
  assert.equal(
    (
      await sessions.TEACHER.context.request.get(
        '/api/submissions/' + report.submissions[0].id + '/result',
      )
    ).status(),
    200,
  )
  for (const role of ['TEACHER', 'ADMIN']) {
    await sessions[role].page.getByRole('button', { name: 'Изход', exact: true }).click()
    await expect(sessions[role].page).toHaveURL(origin + '/login')
    assert.equal((await sessions[role].context.request.get('/api/users/me')).status(), 401)
  }
  report.completedAt = new Date().toISOString()
  report.status = 'passed'
  console.log('Real Portal → browser → API → worker workflow passed.')
} catch (error) {
  report.status = 'failed'
  report.failedStep = step
  console.error('Live smoke failed at: ' + step + ' (' + error.name + ')')
  if (!step.startsWith('Portal login')) console.error(error.message)
  for (const [role, session] of Object.entries(sessions)) {
    if (new URL(session.page.url()).origin === origin) {
      console.log(
        role,
        'visibility:',
        await session.page.evaluate(() => document.visibilityState),
        'path:',
        new URL(session.page.url()).pathname,
      )
      await session.page.screenshot({ path: output + '/failure-' + role + '.png', fullPage: true })
    }
  }
  // Do not log Playwright call arguments: they can include credentials or callback codes.
  process.exitCode = 1
} finally {
  await writeFile(output + '/report.json', JSON.stringify(report, null, 2))
  await browser.close()
}
