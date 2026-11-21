# Frontend delivery and real-user verification

Track unfinished release prerequisites and acceptance criteria in
[remaining work](remaining-work.md). The configuration below is available;
provisioning a production environment remains separate work.

The frontend image builds React with Node 24 on Debian and serves only `dist/`
with Caddy. Node, source files and credentials are absent from the runtime image.
A Debian builder avoids the Rollup native-parser failure observed on Alpine ARM64.
Caddy serves UI and API on one origin; Portal credentials remain in judge-api.

## Local static build and proxy

Keep the existing Portal/infrastructure settings in root `.env`, including
`AUTHENTICATION_MODE=portal`. From the repository root:

```sh
docker compose --env-file .env -f infrastructure/docker/compose.yml -f infrastructure/docker/compose.web.yml --profile api up -d --build --wait
```

Open **http://localhost:8088**. `JUDGE_WEB_PORT` changes the local published port.
The override sets the matching API callback to
`http://localhost:${JUDGE_WEB_PORT:-8088}/login/oauth2/code/portal` and success path
to `/assignments`. Start and finish login on this origin.
To return to Vite on 5173, keep its callback in root `.env` and recreate the API
using only the base Compose file; see [Judge Web](../apps/judge-web/README.md).
The worker still calls the API directly on localhost:8081, never through Caddy.

Verify the running container from `apps/judge-web`:

```sh
node scripts/verify-deployment.mjs
```

It checks SPA deep links, JS assets/cache policy, missing assets, CSP, authentication
routing and blocked internal/operational routes. CI also builds the image and tests
its routing with an unavailable API; this does not require Portal credentials.

## HTTPS deployment

Prerequisites: a server with Docker Compose 2.24.4+, a DNS name pointing to it,
ports 80/443 reachable, and production credentials registered by the Portal team.
No public domain or production Portal registration has been supplied yet.

Create a deployment-specific `.env` outside version control. Include the existing
infrastructure/worker configuration and these values:

```dotenv
JUDGE_PUBLIC_DOMAIN=judge.example.org
AUTHENTICATION_MODE=portal
PORTAL_ISSUER=https://<production-portal-host>
PORTAL_CLIENT_ID=<registered-production-client-id>
PORTAL_CLIENT_SECRET=<registered-production-client-secret>
PORTAL_ADMIN_SUBJECTS=<production-admin-subjects>
WORKER_TRANSPORT_ENABLED=true
JUDGE_API_BASE_URL=http://localhost:8081
SERVER_PORT=8081
```

The hostname is a placeholder. Register this exact HTTPS callback with Portal:
`https://judge.example.org/login/oauth2/code/portal`. The production override sets
this callback from `JUDGE_PUBLIC_DOMAIN`, enables frontend login redirects, and
turns off OpenAPI. Production identities must not use the resettable staging issuer.

```sh
docker compose --env-file /path/to/deployment.env -f infrastructure/docker/compose.yml -f infrastructure/docker/compose.web.yml -f infrastructure/docker/compose.web-production.yml --profile api config --quiet
docker compose --env-file /path/to/deployment.env -f infrastructure/docker/compose.yml -f infrastructure/docker/compose.web.yml -f infrastructure/docker/compose.web-production.yml --profile api up -d --build --wait
```

Caddy obtains and renews certificates automatically. Preserve the `caddy-data`
volume alongside the existing database/storage backups. Infrastructure and direct
API ports stay bound to loopback in base Compose. `/internal/**`, `/actuator/**`
and documentation endpoints are denied by the public proxy; the worker connects
to the private API address with its separate service credential.

Provision the worker/toolchains on the deployment host as described in
[worker transport](worker-transport.md). The local launcher reads root `.env`;
for a separately located production environment use your service manager to inject
only the worker/Redis variables, not Portal/database/storage secrets.

The UI uses HttpOnly, Secure, SameSite=Lax cookies, fresh CSRF per mutation and no
browser bearer-token storage. Caddy preserves the public Host and forwards the
request scheme. API/auth routes run before SPA fallback; their errors never become
an HTML index page. Hashed assets are immutable; the index is revalidated.

## Opt-in end-to-end smoke

This test creates real data. Use dedicated fictitious Portal staging accounts for
TEACHER, STUDENT and ADMIN; supply the required permissions and explicitly allow
only that test administrator's subject in the local API. Do not reuse other teams'
accounts. Prepare Docker toolchains and run the worker first.

Store a mode-600 JSON file outside version control, shaped as follows (placeholders):

```json
{
  "TEACHER": { "email": "teacher@example.test", "password": "test-password" },
  "STUDENT": { "email": "student@example.test", "password": "test-password" },
  "ADMIN": { "email": "admin@example.test", "password": "test-password" }
}
```

From `apps/judge-web`:

```sh
JUDGE_SMOKE_ACCOUNTS=/absolute/path/accounts.json node scripts/live-smoke.mjs
```

The default origin is Vite on 5173. For the container set
`JUDGE_SMOKE_ORIGIN=http://localhost:8088` and start the API with the web override.
`JUDGE_SMOKE_ISSUER` can select the matching test Portal. The script performs real
browser logins, creates/publishes a task with public and hidden tests, submits Java
solutions yielding ACCEPTED/WRONG_ANSWER/COMPILATION_ERROR, verifies score/privacy,
history, leaderboard, teacher review, admin status changes and logout. It restores
the student's access after the disable check and archives its successful test task.
Each run creates a new task and preserves grading history; it does not delete data.
If a run fails, review its report and archive any unfinished test task explicitly.

Reports and result screenshots go to ignored `.local/frontend-smoke/`. No tracing,
password logging or persisted browser sessions are enabled. The script deliberately
is not part of default CI because staging is shared, resettable and may be asleep.

## Verification recorded on 21 November 2026

The container at localhost:8088 passed the full live script against Portal staging:
three roles, publication, all three Java verdicts, hidden-result redaction, history,
leaderboard, teacher review, admin disable/re-enable, archive and logout. The local
report is in ignored `.local/frontend-smoke/report.json`. Dedicated test accounts
are stored locally with mode 600; no credentials or browser sessions are committed.
HTTPS configuration was validated with a placeholder hostname, but certificate
issuance and public production access await a real domain and Portal registration.
