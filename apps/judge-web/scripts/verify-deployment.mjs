import assert from 'node:assert/strict'
import { chromium, expect } from '@playwright/test'
const origin = process.env.JUDGE_SMOKE_ORIGIN ?? 'http://localhost:8088'
const withoutApi = process.env.JUDGE_SMOKE_WITHOUT_API === 'true'
const get = (path, options = {}) => fetch(origin + path, { redirect: 'manual', ...options })
const index = await get('/assignments/example/manage')
assert.equal(index.status, 200)
assert.match(index.headers.get('content-type'), /text\/html/)
assert.equal(index.headers.get('cache-control'), 'no-cache')
assert.match(index.headers.get('content-security-policy'), /frame-ancestors 'none'/)
const html = await index.text()
const asset = html.match(/src="(\/assets\/[^" ]+\.js)"/)?.[1]
assert.ok(asset)
const js = await get(asset)
assert.equal(js.status, 200)
assert.match(js.headers.get('cache-control'), /immutable/)
for (const path of [
  '/internal/worker/v1/submissions',
  '/internal',
  '/actuator/health',
  '/v3/api-docs',
  '/swagger-ui/index.html',
  '/assets/missing.js',
]) {
  const response = await get(path)
  assert.equal(response.status, 404, path)
  assert.doesNotMatch(await response.text(), /<div id="root">/, path)
}
assert.equal((await get('/assignments', { method: 'POST' })).status, 405)
const identity = await get('/api/users/me')
assert.equal(identity.status, withoutApi ? 502 : 401)
assert.doesNotMatch(await identity.text(), /<div id="root">/)
if (!withoutApi) {
  const login = await get('/oauth2/authorization/portal')
  assert.equal(login.status, 302)
  const url = new URL(login.headers.get('location'))
  assert.equal(url.searchParams.get('code_challenge_method'), 'S256')
  assert.ok(url.searchParams.get('scope').includes('unicloud'))
}
const browser = await chromium.launch()
try {
  const page = await browser.newPage()
  const failures = []
  page.on('pageerror', () => failures.push('page error'))
  page.on('console', (message) => {
    if (message.text().includes('Content Security Policy')) failures.push('CSP error')
  })
  await page.goto(origin + '/login')
  if (!withoutApi)
    await expect(page.getByRole('link', { name: 'Вход през UniCloud', exact: true })).toBeVisible()
  else await expect(page.getByRole('alert')).toBeVisible()
  assert.deepEqual(failures, [])
} finally {
  await browser.close()
}
console.log('Static build, SPA fallback, assets, CSP and private-route isolation passed.')
