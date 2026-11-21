# Judge Web

React + TypeScript frontend for Portal SSO, profiles, assignments, student uploads,
submission history, evaluation results, leaderboards, assignment/test editing
and administration of local Judge access.

See [remaining work](../../docs/remaining-work.md) for release prerequisites,
form recovery, error handling, browser coverage and future product features.

## Local startup

Use Node 22.12+ (24 recommended) and the Judge API on port 8081. Set these in
the root `.env` alongside the existing Portal and infrastructure credentials:

```dotenv
AUTHENTICATION_MODE=portal
SERVER_PORT=8081
PORTAL_REDIRECT_URI=http://localhost:5173/login/oauth2/code/portal
PORTAL_LOGIN_SUCCESS_PATH=/assignments
```

Portal staging accepts this callback; production requires exact HTTPS registration.
Never put the backend client secret in a `VITE_` variable or frontend source.

From the repository root:

```sh
docker compose --env-file .env -f infrastructure/docker/compose.yml --profile api up -d --build --wait
cd apps/judge-web
npm ci
npm run dev
```

Open **http://localhost:5173** and start login here, rather than on port 8081.
Vite proxies `/api`, `/auth`, `/oauth2`, and `/login/oauth2` to the backend, preserving
the browser host. Override `JUDGE_WEB_API_TARGET` in the dev process if needed.
No frontend `.env` is required. Spring does not load the root `.env` automatically
when launched from IntelliJ; configure its environment variables separately.

The browser uses the HttpOnly session cookie. Logout fetches fresh CSRF and sends
POST `/auth/logout`. It ends the Judge session, not the Portal session. Expired
sessions return to login; outages offer retry without automatic redirect loops.

The default backend success path stays `/api/users/me` for API-only use. Setting
`/assignments` enables frontend redirects; failed login returns to `/login?error=sso`
without provider details or tokens. Recreate the API container after env changes.

For grading, enable worker transport and configure the separate worker credential
in root `.env`, recreate the API, then start the local worker in another terminal:

```sh
mvn -pl services/judge-worker -am package -DskipTests
python3 infrastructure/operations/run-local-worker.py
```

See [worker setup](../../docs/worker-transport.md#local-worker-with-the-frontend)
for environment settings and Docker toolchain prerequisites. Without a worker,
accepted submissions wait for evaluation.

## Student workflow

- `/assignments/:id`: upload one UTF-8 source file as STUDENT. Languages and size
  limits come from `/api/submissions/options`; the API validates acceptance.
- `/submissions`: own history with language/status filters and pagination.
- `/assignments/:id/submissions`: assignment history, scoped by backend rights.
- `/submissions/:id`: result and available test details. Pending/running results
  refresh every three seconds while the tab is visible; terminal results stop.
  Network failures offer manual retry and retain the last available result.
- `/assignments/:id/leaderboard`: server-ranked scores, without links to other
  students' private submissions.

Every upload obtains fresh CSRF and sends an idempotency key. If acceptance is
uncertain, retry uses the same key and file. Only the key and a fingerprint
(language, filename, SHA-256) remain in this tab's sessionStorage, never source or
authentication tokens. After reload, reselect the identical file/language.
Choosing another solution requires confirmation because the first may be accepted.
Null grades stay unavailable; hidden test data and compiler diagnostics are not
invented. ADMIN and TEACHER accounts cannot upload as students.

## Teaching and administration

TEACHER and ADMIN can create assignments at `/assignments/new`. The assignment's
management link opens `/assignments/:id/manage`: metadata, public/hidden tests,
publication, revision confirmation, archiving and permitted deletion. Teachers see
only their own assignments; administrators can manage all. Changing published
metadata or tests creates a draft; copied test IDs are reloaded before more edits.
Archived assignments are read-only. Points are sent as decimal strings and browser
local deadlines are converted to UTC. Execution limits still use worker defaults;
there are no per-assignment time/memory fields in the current API.

Students also see the public examples returned by the API on the assignment page.
ADMIN has `/admin/users` for combined username/email/role/access searches and
pagination, plus `/admin/users/:id` to inspect a user and enable/disable Judge access.
Portal roles and account creation are not edited here. Status changes require
confirmation; the UI does not offer to disable the currently signed-in administrator.

Writes fetch fresh CSRF, disable repeated/concurrent actions on the management
page and retain entered fields on a rejected request. After a network/server error,
check current data before retrying: assignment/test creation has no idempotency
contract, unlike student submission upload. The UI requires explicit confirmation
before repeating an uncertain write.

## Verification

```sh
npm run api:check
npm run lint
npm run format:check
npm run build
npx playwright install chromium
npm test
```

Playwright checks desktop/mobile views, role context, profile/detail navigation,
filters, pagination, expired sessions, CSRF mutations, file validation, idempotent
retry, polling, hidden test data and outages using intercepted API responses.
These tests do not prove real Portal login or browser-to-worker grading. Java
`PortalAuthenticationIT` exercises Spring OAuth against a controlled OIDC provider,
including frontend redirects and cross-replica sessions.
`WorkerTransportIT` uses real PostgreSQL, MinIO, Redis and Docker Java grading
for accepted, wrong-answer and compilation-error results.

Real smoke test: sign in through Portal, check the role and assignments, reload,
open the profile and sign out. Use separate STUDENT, TEACHER and ADMIN accounts.
An empty assignments page is valid on a new database; frontend inserts no demo data.
With a published assignment and running worker, use STUDENT to submit a correct
solution, a wrong answer and invalid source; confirm each terminal verdict and
the history/leaderboard. The opt-in smoke script has passed this workflow against
Portal staging through the Caddy container, using dedicated test accounts.

## API contract

`openapi.json` is the backend snapshot; `src/api/schema.d.ts` is generated. After
changing the backend contract, start the updated API and run from this directory:

```sh
curl --fail --silent --show-error http://localhost:8081/v3/api-docs --output openapi.json
npm run api:generate
npm run build
```

Commit both files. `api:check` checks generation consistency with the snapshot; it
does not contact live API. Review the snapshot alongside backend changes. Titles
and descriptions render as text, never unsanitized HTML. Backend authorization
remains authoritative regardless of navigation visibility.

## Deployment and real Portal smoke

Build writes `dist/`. A Caddy container now serves the build and proxies API/auth
routes on the same origin. Production uses automatic HTTPS once a domain and
production Portal registration are available. See [delivery instructions](../../docs/frontend-deployment.md)
for Compose overrides, routing checks and the opt-in real-user smoke script.
Cross-origin CORS or browser token storage is not needed.
