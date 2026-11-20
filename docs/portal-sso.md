# UniCloud Portal SSO and calls between modules

Judge supports two explicit authentication modes: `local` (the existing default)
and `portal`. Portal mode implements browser OIDC login and an API resource server.
It does not issue Judge JWTs, accept local passwords, or fall back to local tokens.
The worker keeps its separate service credential in both modes.

## Findings from the Portal contract

Reviewed on 2026-11-19:

- [Official SSO guide](https://github.com/rata5/unicloud-portal/blob/main/docs/sso.md).
- [Live staging discovery](https://unicloud-portal-staging.onrender.com/.well-known/openid-configuration).
- [Client registration](https://github.com/rata5/unicloud-portal/blob/main/src/main/java/bg/tusofia/unicloud/portal/sso/application/SsoClientRegistrationService.java).
- [Token customization and UserInfo](https://github.com/rata5/unicloud-portal/blob/main/src/main/java/bg/tusofia/unicloud/portal/sso/config/SsoAuthorizationServerConfig.java).

The issuer is `https://unicloud-portal-staging.onrender.com`. Modules are confidential
clients using authorization code, mandatory S256 PKCE and `client_secret_basic`.
Request `openid profile email unicloud`. There is no refresh grant for registered modules.

Two details affect API integration:

1. A module's access token has that module's client ID as its audience. A library
   token cannot authenticate Judge, even though both trust the same signing key.
   Portal API tokens (`aud=unicloud-api`) and ID tokens are also rejected by Judge.
2. The Portal adds `role`, `permissions` and student data to the **ID token**, not
   the access token. Judge verifies access JWTs and then calls `/userinfo` with
   that access token. It checks that the returned subject equals the signed subject.
   UserInfo also checks the authorization store, revoked tokens and disabled users.

Discovery advertises `client_credentials`, refresh and token exchange as server
capabilities. However, the actual module-registration code authorizes only
`authorization_code`. Discovery alone does not grant a module permission to use
the other flows. These flows must be enabled and constrained by the Portal team.

The guide describes claims captured at token issuance and advises logging in after
permission changes. The current UserInfo implementation reads the user database
on each call. Judge therefore uses the latest UserInfo permissions on each protected
request; it does not rely on stale ID-token role claims for ongoing API access.

## Configuration and staging setup

Register a dedicated module at [staging tools](https://unicloud-portal-staging.onrender.com/staging).
Keep the returned secret in the server's secret configuration. Example settings:

~~~dotenv
AUTHENTICATION_MODE=portal
PORTAL_ISSUER=https://unicloud-portal-staging.onrender.com
PORTAL_CLIENT_ID=judge-api
PORTAL_CLIENT_SECRET=<the secret for your actual registration>
PORTAL_REDIRECT_URI=http://localhost:8080/login/oauth2/code/portal
PORTAL_ADMIN_SUBJECTS=
PORTAL_REQUEST_TIMEOUT=10s
~~~

Use the actual registered client ID if `judge-api` is already taken. It is also
the required access-token audience. Local RSA keys and administrator bootstrap
credentials are not needed in Portal mode. The default remains `local` so existing
environments are not switched before a Portal client has been configured.

Start the infrastructure and API as usual. Open
`http://localhost:8080/oauth2/authorization/portal` in the browser. Spring Security
performs discovery, creates state/nonce/PKCE, exchanges the code using HTTP Basic,
validates the ID token and redirects to the fixed local `/api/users/me` path.
An error returns a sanitized Problem Detail; it never starts a redirect loop.
No API endpoint returns Portal tokens to browser JavaScript.

Production requires a separately registered HTTPS callback with an exact match.
Configure `PORTAL_ISSUER` for that environment. HTTP is permitted only for loopback
development URLs. The application validates the authentication-mode setting and
fails startup for unknown values or missing Portal configuration.

Warm staging before starting the API or testing login: discovery and key fetching
may otherwise time out during its 1–3 minute cold start. `PORTAL_REQUEST_TIMEOUT`
controls per-request UserInfo calls (1 second–3 minutes); OIDC discovery and code
exchange use the library's HTTP defaults. A timed-out code exchange must not be
blindly retried: codes are single-use. Start a new login instead.

The staging database is replaced periodically and is shared across teams. Use
invented users; re-register the client if staging loses it. Real staging client
registration/login is an environment setup step, separate from the automated
protocol tests. No shared staging users or registrations were changed by this work.

## Browser sessions and API calls

~~~mermaid
sequenceDiagram
    participant Browser
    participant Judge
    participant Portal
    Browser->>Judge: GET /oauth2/authorization/portal
    Judge-->>Browser: Redirect with state, nonce, PKCE challenge
    Browser->>Portal: Authorization/login
    Portal-->>Browser: Redirect to Judge callback with code and state
    Browser->>Judge: Callback
    Judge->>Portal: Code + secret via Basic + PKCE verifier
    Portal-->>Judge: ID token and access token
    Judge-->>Browser: HttpOnly session cookie; local redirect
    Browser->>Judge: API request with session cookie
    Judge->>Portal: /userinfo with server-held access token
    Judge->>Judge: Resolve local user; enforce role and ownership
    Judge-->>Browser: Judge response, without Portal tokens
~~~

`JUDGE_SESSION` is HttpOnly, Secure, SameSite=Lax, path `/`, with no shared Domain.
Session inactivity timeout is 30 minutes. Each protected request revalidates the
stored access JWT and UserInfo, so access also ends when that token expires
(30 seconds of allowed clock skew). No refresh token or refresh endpoint exists.
Go through OIDC again when needed; an active Portal session avoids another password.

Sessions, pending OIDC state/PKCE verifiers and authorized-client tokens are held
in Spring Session Redis under judge:portal:sessions:*. API replicas use the same
private Redis store and Portal client settings; affinity is not required. API
restart preserves sessions; Redis session loss requires a new login.
Tokens belong in that protected, expiring session storage, never in the users table
or browser local/session storage. Protect session backups and restrict access to
the shared store because an access token is a bearer credential.

For a browser mutation:

1. `GET /auth/csrf` using the session cookie. The no-store response contains
   `headerName` and `token` (a CSRF token, not a Portal token).
2. Send the returned header and cookie with the POST/PUT/DELETE request. Fetch a
   new CSRF token after login because authentication rotates it.
3. `POST /auth/logout` with the CSRF header ends the Judge session and returns 204.

Use same-origin browser requests; cross-origin cookie/CORS access is not enabled.
Logout is module-local. It does not end the Portal SSO session or other modules'
sessions. The optional Portal global logout redirect is not implemented.

Server-to-server requests use `Authorization: Bearer <Portal access token for Judge>`.
They are stateless and require no CSRF token. If an Authorization header is present,
Judge never falls back to a browser session when the supplied token is invalid.
Only the configured Portal issuer, RS256 signature, valid timestamps, Judge audience
and `openid unicloud` scopes are accepted; UserInfo must also accept the token.

~~~http
GET /api/users/me HTTP/1.1
Host: judge.example.edu
Authorization: Bearer <access token whose aud includes the Judge client ID>
~~~

`401` means an invalid, expired, revoked or incorrectly addressed token; `403`
means the identity lacks Judge access or the operation violates local authorization.
UserInfo outages return sanitized `503 IDENTITY_PROVIDER_UNAVAILABLE`. There is
no fallback to cached privileges. This makes Portal availability a dependency of
protected requests; introducing a short cache would explicitly trade revocation
latency for availability and is not part of this implementation.

## What Judge stores about a user

| Input | Persistent use in Judge |
| --- | --- |
| Configured issuer and validated `sub` | Unique external identity mapped to a stable local UUID |
| `email` | Optional local contact/profile snapshot, updated after successful validation |
| `role` and `permissions` | Evaluate the policy below; store the resulting Judge role |
| `name`, `given_name`, `family_name`, `student` | Not currently stored; no grading operation needs them |
| Access token and ID token | Server session only for browser login; never stored in `users` |
| Local enabled flag | Judge-owned access suspension; Portal cannot automatically re-enable it |

The complete UserInfo payload is not stored. Raw permission strings are evaluated
per request and do not become arbitrary Spring authorities. Password hashes are NULL
for external users. Tasks, submissions and results keep referencing the local UUID.
Changing email or issuing a new token does not create a new identity or change ownership.

| Portal identity | Judge policy |
| --- | --- |
| STUDENT | Requires STUDENT_PROFILE_READ_OWN and COURSE_OFFERING_READ; student operations, own submissions/results |
| TEACHER | Requires COURSE_OFFERING_READ; teacher operations limited by existing assignment ownership |
| ADMIN | Requires the subject to be explicitly listed in PORTAL_ADMIN_SUBJECTS; full Judge administration |
| INSPECTOR or unknown role | No Judge capability mapping; 403 |

This is an explicit initial policy, not a claim that Portal permissions model Judge
capabilities. Portal currently has no assignment/evaluation permissions. In particular,
STAFF_CREATE alone does not grant Judge administration, and student-profile permissions
do not grant access to somebody else's source or scores. Course/enrollment membership
is not imported by this change; current Judge ownership rules still apply.

Portal role changes synchronize on a successful request. External role changes advance
the local authentication version so in-flight requests with an older role are rejected.
External roles cannot be edited through Judge's local role-change endpoint. Judge
administrators can still suspend a local membership. The local last-admin database
guard does not prevent an identity-provider role revocation for an external user.

### Existing accounts and data

Flyway V11 adds nullable external identity columns, a unique (issuer, subject) index
and support for passwordless users. Existing UUIDs, passwords and grading history
are retained. Local email uniqueness remains case-insensitive. External accounts
may share an email with a local account without being linked to it.

**An existing account is never linked automatically by email.** Without an explicit
mapping, the first Portal login creates a new local UUID and therefore does not inherit
an old teacher's tasks or a student's past submissions. Before a real cutover, obtain
a verified mapping of old Judge UUIDs to Portal subjects and apply it as a reviewed
data migration. For each mapped row, set issuer/subject, clear password_hash and advance
token_version together in a transaction; retain its UUID. Resolve any already-created
external record before mapping (never blindly merge grading ownership). The local
last-admin guard still applies when converting a local administrator. No account
linking or existing-data migration was executed against a running environment.

Staging may reuse subjects after its database reset. Use a disposable Judge database
for staging or clear/remap staging identity projections as part of that reset. Never
point production grading history at the resettable staging issuer.

## Proposal: direct requests from other modules

**Recommended: audience-specific token exchange at the Portal (RFC 8693).**
This is a proposed extension, not a feature granted to current module registrations.

~~~mermaid
sequenceDiagram
    participant Module as Other module backend
    participant Portal
    participant Judge
    Module->>Portal: Authenticated token exchange; user token; audience=Judge
    Portal->>Portal: Check calling module, user, allowed target and scopes
    Portal-->>Module: Short-lived delegated access token for Judge
    Module->>Judge: Authorization: Bearer delegated token
    Judge->>Judge: Verify issuer/audience and caller capabilities
    Judge->>Portal: Validate identity/revocation using agreed UserInfo contract
    Judge-->>Module: Authorized response
~~~

Proposed exchange request, made only by the other module's backend:

~~~http
POST /oauth2/token
Authorization: Basic <that module's own client ID and secret>
Content-Type: application/x-www-form-urlencoded

grant_type=urn:ietf:params:oauth:grant-type:token-exchange
&subject_token=<the user's access token for the calling module>
&subject_token_type=urn:ietf:params:oauth:token-type:access_token
&requested_token_type=urn:ietf:params:oauth:token-type:access_token
&audience=judge-api
&scope=openid unicloud judge.results.read
~~~

Use the actual Judge client ID as audience until a separate resource audience is
agreed. Proposed narrow capabilities include `judge.assignments.read`,
`judge.submissions.create` and `judge.results.read`. These are **new proposed scopes**;
the current Portal registration and Judge authorization do not implement them yet.

The Portal extension must:

- Explicitly authorize the calling client's exchange grant, allowed Judge target
  and allowed scope subset. Never accept arbitrary requested audiences or scopes.
- Validate that the subject token is current and belongs to that calling module;
  bind the authenticated client to the delegation (for example `act`/`client_id`).
- Preserve the user's stable `sub` and Portal `iss`, issue a short-lived access token
  addressed to Judge, and prevent privilege amplification or unrestricted chaining.
- Define how delegated tokens obtain role/permission data. The current Judge adapter
  requires UserInfo to accept them and return matching sub/permissions, with openid
  and unicloud scope; alternatively add a separately reviewed introspection contract.
- Register/revoke the exchanged authorization so replay/revocation semantics remain
  enforceable, and audit caller, target, subject and granted scopes without token values.

The corresponding Judge extension must enforce the delegated caller and granted
Judge scopes **in addition to** user role, enabled status and ownership, and restrict
delegated tokens from user-administration, metrics and worker routes. This caller/scope
policy is deliberately not claimed as implemented by the current generic user-token
resource server. Agree and test the issued-token contract before enabling delegation.

Until that extension is available, another module can link the browser to Judge's
OIDC entry point. The Portal SSO cookie makes that a passwordless transition when the
user is already signed in. This works today, but is a browser handoff, not a general
server-to-server delegation mechanism. Do not share Judge's client secret with other
modules, accept tokens for their audiences, or pass Portal tokens through JavaScript.

### Jobs without a user

Nightly synchronization and automated reporting need a distinct `client_credentials`
grant with a Judge audience and service-specific scopes. Such a caller is a service,
not a STUDENT/TEACHER user and must not be silently provisioned into the users table.
The current Portal registration and Judge user adapter do not support that contract.
Add a separate service principal and an explicit endpoint/capability policy after
agreement with the Portal team. The evaluation worker's existing credential remains
private to the worker routes and is not a general inter-module credential.

## Verification

`PortalAuthenticationIT` runs actual OIDC and resource-server filters against a local
HTTP identity provider with signed RSA tokens and real PostgreSQL. It covers code
exchange with HTTP Basic and S256, state/nonce/replay errors, secure cookies, CSRF,
module logout, audience/issuer/signature/time/scope rejection, numeric subjects,
concurrent provisioning, email collision isolation, UserInfo failure/revocation,
role changes, local suspension, assignment ownership and worker separation.
Configuration tests cover invalid mode/URLs and secret redaction. Existing local
authentication, administration and grading suites remain part of the full build.

~~~sh
mvn -o verify -Pintegration-tests
~~~

These tests verify the protocol boundary without using shared staging credentials.
A real staging smoke test still requires registering the module and supplying its
secret in the runtime environment; production additionally requires Portal-approved
callback addresses and a verified existing-user migration.
