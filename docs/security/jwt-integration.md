# UniCloudAutoJudge — JWT and Portal SSO Integration

## 1. Purpose

UniCloudAutoJudge supports authentication through the shared UniCloud Portal.

The Portal is the identity provider. The Judge API validates the identity and determines the user's permissions.

The project also retains local authentication as a separate mode.

## 2. Authentication modes

The application has two authentication modes:

- `local` — local username/password authentication and RS256 JWT validation.
- `portal` — authentication through the UniCloud Portal using OpenID Connect (OIDC).

Portal mode is enabled with:

`security.authentication-mode=portal`

Portal configuration uses the `security.portal` properties.

The Portal client ID, client secret, issuer and callback URL must be configured through protected environment variables or equivalent secure configuration. Never commit real credentials.

## 3. Portal OIDC login

Portal authentication uses:

- OpenID Connect.
- Authorization Code Flow.
- PKCE with S256.
- Client authentication using `client_secret_basic`.
- Scopes: `openid profile email unicloud`.

The module is registered as a confidential OIDC client.

The browser starts authentication through:

`/oauth2/authorization/portal`

After successful authentication, the Portal redirects the browser to the registered callback:

`/login/oauth2/code/portal`

The application then creates a server-side authenticated session.

PKCE is explicitly enabled in the application, including for the confidential client.

## 4. Browser session security

The browser authentication flow uses server-side sessions.

The application stores OIDC session information and authorized clients in Redis.

Configuration includes:

- Redis session namespace: `judge:portal:sessions`
- Maximum inactive session time: 30 minutes
- Session cookie name: `JUDGE_SESSION`
- HttpOnly cookie enabled
- Secure cookie enabled by default
- SameSite set to `Lax`
- Session invalidation during logout

The logout endpoint is:

`/auth/logout`

Cookie-authenticated requests retain Spring Security CSRF protection. The frontend can obtain the CSRF token from:

`GET /auth/csrf`

The endpoint returns the CSRF header name and token and uses no-store caching.

## 5. API Bearer-token authentication

The API also supports stateless authentication through an Authorization header:

`Authorization: Bearer <access_token>`

Requests containing an Authorization header use a separate security filter chain.

This chain:
- Does not use browser sessions.
- Validates the access token.
- Does not allow a browser cookie to rescue an invalid Bearer token.
- Uses the same API authorization rules.

The API denies the old local authentication endpoints under `/api/auth/**` when Portal mode is active.

## 6. Access-token validation

The Portal access token is validated using the Portal's published JWK Set.

The implementation requires:

- RS256 signature verification.
- Correct issuer.
- Audience containing the registered module client ID.
- Valid expiration and timestamp claims.
- A valid subject (`sub`).
- Required `openid` and `unicloud` scopes.

The implementation does not accept an ID token as an API credential because the required API scope validation is applied.

The token is validated before the application uses its identity.

## 7. UserInfo and permission mapping

The Portal access token does not contain the final Judge user roles.

The application calls the Portal UserInfo endpoint using the access token.

The identity service checks:
- The subject is present and matches the token subject.
- Permissions are returned in the expected format.
- The Portal role has the permissions required for Judge access.
- The email, when supplied, is valid.
- The local external-user account is enabled.

Current role mapping:

| Portal role | Judge requirement |
|---|---|
| STUDENT | `STUDENT_PROFILE_READ_OWN` and `COURSE_OFFERING_READ` |
| TEACHER | `COURSE_OFFERING_READ` |
| ADMIN | Subject must be listed in `adminSubjects` |
| INSPECTOR | No Judge role is assigned |

Users without an accepted role and permission combination are rejected.

External users are linked using the Portal issuer and subject, rather than relying only on email. Existing user roles and email information are synchronized from Portal claims.

## 8. Authorization

The API uses Spring Security role-based authorization.

- `/api/admin/**` requires ADMIN.
- `/api/teacher/**` requires TEACHER or ADMIN.
- Other `/api/**` endpoints require authentication.
- Health endpoints and selected API documentation endpoints are public.
- Unmatched endpoints are denied.

Method-level security is also enabled.

## 9. Error handling

The Portal integration uses controlled error responses:

| Situation | Response |
|---|---|
| Invalid or expired token | Authentication failure |
| User has no permitted Judge role | HTTP 403 |
| Identity provider temporarily unavailable | HTTP 503 |
| Successful authentication | Authenticated session or API request |

Provider error details, authorization codes and tokens must not be exposed in frontend redirect URLs.

## 10. Security responsibilities

The module team must:

- Register the module in the shared Portal.
- Use the correct client ID and client secret.
- Configure the registered callback URL.
- Request all required scopes, including `unicloud`.
- Keep client secrets on the server.
- Use HTTPS outside local development.
- Never put tokens or client secrets in frontend code.
- Keep Worker service authentication separate from student authentication.

Security review must also verify that:
- Production secrets are stored securely.
- Callback URLs match the registered URLs.
- The correct client ID is used for audience validation.
- Redis sessions are protected.
- Cookie and CSRF settings remain enabled.
- Portal role mappings match the permissions required by Judge.

## 11. Related files

Main implementation:

- `PortalSecurityConfiguration.java`
- `PortalSettings.java`
- `PortalBrowserSettings.java`
- `PortalIdentityService.java`
- `PortalJwtConverter.java`
- `PortalSessionConfiguration.java`
- `PortalSessionFilter.java`
- `PortalSessionController.java`
- `PortalLoginHandler.java`
- `PortalProblemHandler.java`

Additional integration instructions:

- `docs/portal-sso.md`
- `docs/security/README.md`

## 12. Important note

Portal SSO provides identity and permissions. It does not make submitted student code safe.

The Worker and execution sandbox must remain separate security boundaries, with their own service authentication and isolation controls.