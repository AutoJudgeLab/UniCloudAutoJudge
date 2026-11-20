# Authentication and authorization

Select AUTHENTICATION_MODE=local (default) or portal. This page describes local
mode. The implemented UniCloud OIDC integration, cookie/CSRF flow, identity migration
and direct module-to-API proposal are in [Portal SSO](portal-sso.md). Portal mode
disables local registration/login, password services and RSA signing keys.

Stage 3 adds local registration/login and a Spring Security OAuth2 resource-server
boundary. Access tokens use RS256 through Spring Security's Nimbus JWT encoder and
decoder. Passwords use BCrypt with cost 12. Stage 4 adds
[administrator user management](user-administration.md), including teacher role
changes and persistent token revocation. Refresh tokens and session cookies are
not implemented.

## Signing keys and startup

Local authentication mode requires a matching RSA key pair supplied through environment variables:

| Variable | Meaning |
| --- | --- |
| `JWT_PRIVATE_KEY_BASE64` | Base64-encoded PKCS#8 DER RSA private key. Required. |
| `JWT_PUBLIC_KEY_BASE64` | Base64-encoded X.509 SubjectPublicKeyInfo DER RSA public key. Required. |
| `JWT_ISSUER` | Expected issuer; defaults to `online-judge`. |
| `JWT_AUDIENCE` | Expected audience; defaults to `judge-api`. |
| `JWT_ACCESS_TOKEN_TTL` | Access-token lifetime; defaults to `15m`, permitted range `1m`–`60m`. |

RSA keys must be at least 2048 bits. Missing, invalid, undersized, or mismatched
keys fail startup without printing their values. Base64 is a transport encoding,
so treat the private-key variable as a secret. Reuse the same pair across API
instances and restarts; generating a different pair invalidates existing tokens.

Generate a local key pair once, from the repository root:

```sh
umask 077
mkdir -p .local/secrets
openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:3072 -out .local/secrets/jwt-private.pem
openssl pkcs8 -topk8 -nocrypt -in .local/secrets/jwt-private.pem -outform DER -out .local/secrets/jwt-private.der
openssl pkey -in .local/secrets/jwt-private.pem -pubout -outform DER -out .local/secrets/jwt-public.der
```

`.local/` is ignored by Git. Export infrastructure configuration first, then the
keys, so the empty key placeholders in `.env` do not replace the exported values:

```sh
set -a
. ./.env
set +a
export JWT_PRIVATE_KEY_BASE64="$(openssl base64 -A -in .local/secrets/jwt-private.der)"
export JWT_PUBLIC_KEY_BASE64="$(openssl base64 -A -in .local/secrets/jwt-public.der)"
mvn -pl services/judge-api -am install -DskipTests
mvn -pl services/judge-api spring-boot:run
```

These exports keep the key values out of terminal output and shell-history command
text. Alternatively, supply both variables through your environment's secret
configuration. No keys or credentials are committed. Use HTTPS at the deployment
boundary when credentials or bearer tokens travel beyond local development.

## API contract

| Endpoint | Authentication | Success |
| --- | --- | --- |
| `POST /api/auth/register` | Public | `201` with the new user's profile DTO. |
| `POST /api/auth/login` | Public | `200` with `accessToken`, `tokenType`, and `expiresIn`. |
| `GET /api/users/me` | Bearer JWT | `200` with the authenticated user's profile DTO. |

Registration JSON fields:

```json
{
  "username": "student01",
  "email": "student01@example.test",
  "password": "<your strong password>"
}
```

Replace the password placeholder with your own password meeting the policy below.
Registration always assigns STUDENT. A client-supplied `role`, `enabled`, `userId`,
or any other unknown field produces `400` and creates no account. DTOs have no
role input field, and the service assigns STUDENT explicitly.

- Usernames contain 3–50 ASCII letters/digits/dots/underscores/hyphens and start
  with a letter or digit. Leading/trailing whitespace is stripped; stored names
  are lowercase. The `@` character is excluded to distinguish names from emails.
- Emails must be valid, at most 254 characters, and are stripped/lowercased.
- Username and email uniqueness is case-insensitive, including database indexes
  that protect concurrent registration. A conflict returns `409`.
- Passwords contain at least 12 characters, uppercase and lowercase letters, a
  digit, and a symbol, with no whitespace/control characters. They may use at most
  72 UTF-8 bytes to avoid BCrypt truncation. Passwords are never trimmed.

Login uses a single `identifier` field accepting either username or email:

```json
{
  "identifier": "student01@example.test",
  "password": "<your password>"
}
```

The response contains a signed `accessToken`, `tokenType: Bearer`, and `expiresIn`
in seconds (900 by default). Supply it as `Authorization: Bearer <accessToken>`
to protected endpoints. Wrong passwords, unknown accounts, and disabled accounts
return the same generic `401` response. Unknown-account login still performs a
BCrypt comparison. Tokens and password-bearing DTOs redact diagnostic string
representations. Password hashes and signing secrets never appear in API responses.

User responses contain only `id`, `username`, `email`, `role`, `enabled`, and
`createdAt`. `/api/users/me` obtains the UUID from the security context; query
parameters such as `userId` cannot select another account.

Errors use `application/problem+json` with `type`, `title`, `status`, `detail`,
`instance`, and a stable `code`. Validation errors additionally contain an
`errors` object mapping field names to messages, never rejected values. Common
codes are `VALIDATION_FAILED`, `INVALID_REQUEST`, `ACCOUNT_CONFLICT`,
`UNAUTHORIZED`, and `FORBIDDEN`. All `401` responses include `WWW-Authenticate:
Bearer`. A valid identity lacking a required role receives `403`.

Swagger UI remains at `/swagger-ui/index.html`; use its Authorize button with the
access token. `/v3/api-docs` documents the bearer scheme, request/response DTOs,
validation constraints, and endpoint error responses.

## Authorization boundary

Login and registration also have [Redis rate limits](#login-and-registration-rate-limits).

Health/documentation GET routes and registration/login POST routes are public.
Other `/api/**` routes require authentication. The `/api/admin/**` namespace
requires ADMIN; `/api/teacher/**` accepts TEACHER or ADMIN. Stage 4 provides the
`/api/admin/users` endpoints. Method-level security also protects the administration
service. Stage 5 adds role checks and assignment ownership enforcement in
`AssignmentService`; see [Assignment management](assignments.md). Submission
authorization is enforced by ownership and role checks; see [submissions](submissions.md).

The decoder pins RS256 and validates signature, issuer, audience, expiration, and
issued-at time, allowing 30 seconds of clock skew. Tokens must have both `exp` and
`iat`, and their subject must be a canonical local user UUID. The token contains
no email or password information. A `role` claim describes the issuance-time role;
authorization uses the current role from PostgreSQL instead of trusting that claim.

On every authenticated request, `LocalUserJwtConverter` verifies that the subject
is a known, enabled local account and verifies the signed integer `token_version`
against that account's current version. It produces an `AuthenticatedUser` carrying
the local ID, current role, and authentication version. Disabling an account revokes
old tokens permanently, including after re-enabling; role changes use the current
database role on subsequent requests. Tokens issued before Stage 4 have no version
claim and require a fresh login. The filter chain uses stateless bearer-header
authentication, with form login, HTTP Basic, logout/session handling, and CSRF
disabled. CSRF is disabled because credentials are not accepted from cookies.

Business code depends on CurrentUser and AuthenticatedUser, not provider-specific claims.
The [Portal adapter](portal-sso.md) maps issuer/subject to the same application UUID boundary.
Local tokens cannot authenticate external accounts; Portal mode accepts only its configured issuer.
Both modes retain local account-status checks and the separate worker authentication chain.

## Initial administrator

Bootstrap is off by default. To provision the first administrator, set:

```dotenv
ADMIN_BOOTSTRAP_ENABLED=true
ADMIN_BOOTSTRAP_USERNAME=
ADMIN_BOOTSTRAP_EMAIL=
ADMIN_BOOTSTRAP_PASSWORD=
```

Supply your own values before starting the API. The username, email, and password
must satisfy the registration policy. There are no default credentials.

The startup runner invokes a transactional bootstrap service. A PostgreSQL advisory
lock serializes concurrent instances. If any local administrator already exists, bootstrap
is a no-op: it never resets a password, promotes another account, changes the
existing identity, or re-enables a disabled administrator. Otherwise it validates
configuration and creates exactly one enabled ADMIN with a BCrypt hash. A username
or email collision with an existing non-admin fails startup without promoting that
account. Invalid configuration also fails startup without logging the password.

After initial provisioning, set `ADMIN_BOOTSTRAP_ENABLED=false`, remove the three
bootstrap credential variables from the deployment environment, and restart.
Normal login continues to use the stored password hash.

V4 adds database protection against deleting, disabling, or demoting the last
active administrator. It serializes membership changes and locks surviving admin
rows, so concurrent transactions cannot remove all administrators, including at
REPEATABLE READ isolation. A conflicting transaction can fail with a serialization
or deadlock error. Stage 4 administrative writes use the same lock before taking
row locks, recheck the acting administrator, and return retryable concurrency
conflicts as `409 CONCURRENT_UPDATE`. General role changes never assign or remove
ADMIN; see [User management](user-administration.md).

V4 also adds case-insensitive unique indexes. If a Stage 2 database contains names
or emails differing only by case, migration fails rather than silently merging
accounts; resolve those identities before upgrading. Earlier migration files are
unchanged.

## Verification and deferred features

```sh
mvn clean verify
mvn clean verify -Pintegration-tests
```

Integration tests require Docker and generate ephemeral RSA keys and database
credentials. They cover registration/validation/conflicts, role-field rejection,
login, missing/forged/expired/invalid-claim JWTs, disabled accounts, role changes,
current-user identity, Problem Details, OpenAPI, bootstrap creation/idempotency/
collisions/concurrency, and last-admin protection. Existing persistence, history,
and health tests remain included.

Refresh tokens, password reset, email verification, key rotation
with overlapping validation keys, additional ADMIN
provisioning are deferred. Stage 8 implements [submission authorization](submissions.md). Worker functionality
uses a separate scoped service credential; local JWTs never grant evaluation rights.

## Login and registration rate limits

Redis-backed limits are enabled by default and shared across API instances:

| Variable | Default |
| --- | --- |
| `AUTH_RATE_LIMIT_ENABLED` | `true` |
| `AUTH_LOGIN_LIMIT` | 5 requests per address per window |
| `AUTH_REGISTER_LIMIT` | 3 requests per address per window |
| `AUTH_RATE_LIMIT_WINDOW` | `1m` (1 second–1 hour) |

Counters are independent for login and registration and include invalid requests. Atomic Lua increments
and expiry prevent concurrency bypasses. Limited requests return `429 RATE_LIMIT_EXCEEDED` with the
remaining `Retry-After` seconds. Redis failures return `503 RATE_LIMIT_SERVICE_UNAVAILABLE`; restoring
Redis restores authentication without an API restart. Existing JWT-authenticated requests and worker
endpoints do not use these counters.

The filter matches decoded servlet paths, including deployments under a context path. Client identity
uses `getRemoteAddr()` and does not trust arbitrary `X-Forwarded-For`. Configure trusted proxies at the
server/ingress boundary and choose thresholds suitable for shared university/NAT addresses.

Business integration suites explicitly disable the limiter so they do not share IP budgets or require
Redis. `AuthRateLimitIT` enables it with isolated Redis and checks registration/login/profile,
concurrent requests, independent limits, expiry and spoofed forwarding headers. Unit tests cover
fail-closed behavior and encoded/context paths. Worker authentication uses its own credential and
filter chain; see [worker transport](worker-transport.md).
