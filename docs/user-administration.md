# Administrator user management

Stage 4 implements user administration in `judge-api`. All four endpoints require
an enabled ADMIN account and a valid bearer JWT. Authorization is enforced both
by the `/api/admin/**` security policy and on `AdminUserService`, so calling the
service directly does not bypass role checks.

## Endpoints

| Method and path | Purpose | Success |
| --- | --- | --- |
| `GET /api/admin/users` | Search, filter, sort, and page users. | `200`, page DTO. |
| `GET /api/admin/users/{userId}` | Retrieve a user by UUID. | `200`, user DTO. |
| `PATCH /api/admin/users/{userId}/role` | Change between STUDENT and TEACHER. | `200`, updated user DTO. |
| `PATCH /api/admin/users/{userId}/status` | Enable or disable an account. | `200`, updated user DTO. |

Every user DTO contains exactly `id`, `username`, `email`, `role`, `enabled`, and
`createdAt`. Password hashes and token-version metadata are never returned.
Swagger UI at `/swagger-ui/index.html` documents the bearer requirement, query
parameters, request bodies, responses, and error cases.

## Listing and searching

Query parameters:

| Parameter | Default | Behavior |
| --- | --- | --- |
| `page` | `0` | Zero-based page number, at least zero. |
| `size` | `20` | Page size between 1 and 100. |
| `sort` | `createdAt,desc` | One `field,direction` pair. |
| `username` | None | Case-insensitive literal substring, up to 100 characters. |
| `email` | None | Case-insensitive literal substring, up to 254 characters. |
| `role` | None | STUDENT, TEACHER, or ADMIN. |
| `enabled` | None | `true` or `false`. |

Allowed sort fields are `id`, `username`, `email`, `role`, `enabled`, and `createdAt`.
Directions are `asc` and `desc`. An ascending ID tie-breaker is appended unless
ID is already the selected field, giving deterministic ordering across pages for
an unchanged dataset. Unknown sort fields and invalid paging/filter values return
`400`. Concurrent inserts/deletes can naturally shift offset-based pages.

All filters are combined with AND. Search text is stripped/lowercased and `%`, `_`,
and backslash are escaped as literals, not accepted as SQL wildcard expressions.
Omitted or blank username/email searches impose no restriction. Substring queries
use JPA criteria and bound values; they do not interpolate SQL.

Example:

```text
GET /api/admin/users?page=0&size=20&sort=username,asc&username=alice&role=TEACHER&enabled=true
```

The page response contains:

```json
{
  "content": [],
  "page": 0,
  "size": 20,
  "totalElements": 0,
  "totalPages": 0
}
```

A page beyond the last page returns empty content with the matching totals.

## Role changes

```json
{ "role": "TEACHER" }
```

Only STUDENT-to-TEACHER and TEACHER-to-STUDENT changes are allowed. Repeating the
current non-administrative role is an idempotent no-op. Disabled students and
teachers can also have their roles changed.

- Requesting ADMIN returns `400 INVALID_ROLE_CHANGE`.
- Changing an existing administrator's role through this endpoint returns
  `409 ADMIN_ROLE_PROTECTED`, even if other administrators exist.
- Any attempt to change the caller's own role returns `403 FORBIDDEN`.
- Unknown enum names, numeric roles, missing roles, and extra body fields return `400`.

The initial administrator bootstrap remains the only implemented administrative
provisioning mechanism. Granting additional ADMIN roles requires a future separate,
explicitly authorized mechanism. This endpoint cannot be used for that purpose.

Role changes take effect on existing tokens through the current-role database
check performed during each authenticated request. JWT role claims are never the
source of authorization. A role change alone does not change the token version.

## Status changes and token invalidation

```json
{ "enabled": false }
```

`enabled` is required; an omitted/null value does not silently disable an account.
Unknown request fields are rejected. Disabling the last active ADMIN returns
`409 LAST_ACTIVE_ADMIN`. An administrator may disable their own account only when
another active administrator remains; the successful response completes normally,
and subsequent requests with the old token are unauthorized.

V5 adds a nonnegative `users.token_version` counter. A database trigger advances
it atomically on every actual enabled/disabled transition, including direct SQL
writes. The counter cannot be decreased. Hibernate retrieves its generated value;
it is not client-editable and is excluded from response DTOs and entity serialization.
Repeating the current status does not advance the counter or revoke tokens.

New access tokens contain a signed integer `token_version` claim. Authentication
requires all of the following on every request:

1. A valid signature, issuer, audience, timestamps, and local user UUID.
2. A currently enabled local user.
3. An integer token version equal to the current database value.

Disabling an account prevents login and rejects all its existing tokens immediately
on subsequent requests. Re-enabling permits a new login but never resurrects tokens
issued before the disable/enable cycle. Token invalidation does not rely on expiry
or an in-memory cache, so it works across API instances sharing PostgreSQL.

**Upgrade behavior:** tokens issued before Stage 4 lack `token_version` and will
receive `401` after deployment. Users must log in again. Existing password hashes,
roles, and other account data are preserved by V5; existing rows start at version 0.
No new environment variables or signing keys are required. The authentication
boundary must retain an equivalent revocation check when adopting an external
authentication provider.

## Concurrent administrative updates

Mutations run in transactions and acquire the same PostgreSQL transaction advisory
lock used by bootstrap and the existing administrator guard, before taking row
locks. This serializes infrequent administrative writes across API instances.
Reads, ordinary authentication, and login do not acquire this advisory lock.

After acquiring it, the service locks and reloads the acting administrator and
checks their current enabled state, role, and authentication version. A request
authorized by the HTTP filter before its caller was disabled, re-enabled, or
demoted cannot proceed using that stale identity. The target user is then locked
for update, checked, and changed. This also prevents concurrent role/status updates
from overwriting each other's changes.

The service checks that disabling an administrator leaves another active ADMIN.
The V4 database trigger remains unchanged and provides an additional invariant
against deletion, disabling, and demotion, including out-of-band writes. Concurrent
administrators cannot disable all active administrators. A database concurrency
failure returns `409 CONCURRENT_UPDATE`; callers can retry.

## Error responses

Errors use the existing `application/problem+json` contract.

| Status | Examples |
| --- | --- |
| `400` | Invalid UUID, pagination, sorting, filter, body, or requested ADMIN role. |
| `401` | Missing/invalid/revoked token; disabled or re-enabled caller using an old token. |
| `403` | STUDENT/TEACHER attempting administration; self-role changes; caller no longer ADMIN. |
| `404` | Target user does not exist. |
| `409` | Protected administrator role, last active administrator, or concurrent update conflict. |

## Verification

```sh
mvn clean verify
mvn clean verify -Pintegration-tests
```

The Testcontainers suite exercises all four endpoints and service-level security,
combined search/filter/pagination behavior, allowed/forbidden sorting, literal
wildcards, safe DTOs, role transitions and forbidden escalation, missing/invalid
requests, status idempotency, permanent token revocation after re-enabling, invalid
version claims, last-admin protection, and concurrent updates. It also upgrades a
V4 schema containing an existing account and checks password preservation and
version advancement. All earlier authentication and grading-history tests remain.

Account deletion, editing username/email/password, and additional ADMIN provisioning
are deferred. Assignment and [submission management](submissions.md) are implemented. The worker remains
intentionally empty.
