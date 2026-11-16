
# Secrets Management, API Rate Limiting and Cloudflare

**Project:** UniCloudAutoJudge  
**Owner:** Security Engineer  
**Status:** Application rate limiting implemented and locally tested; production configuration requires review.

---

## 1. Existing repository safeguards

The reviewed project contains:

- `.gitignore` rules excluding `.env` and `.env.*`.
- `.env.example` with empty configuration placeholders.
- Environment-based configuration for credentials.
- Environment-provided JWT private and public keys.
- Private object-storage bucket design.
- Application-scoped storage credentials.

The reviewed project snapshot did not contain a real `.env` file.

However, checking the current files does not prove that secrets were never committed in Git history.

---

## 2. Secrets management rules

The following rules must be followed:

1. Never commit `.env` files.
2. Never commit private keys, passwords, access tokens or production credentials.
3. Keep `.env.example` limited to empty placeholders.
4. Use separate credentials for PostgreSQL, Redis, object storage and JWT signing.
5. Never give MinIO root credentials to the API or worker.
6. Never pass production secrets into the student-code sandbox.
7. Use a secret manager or appropriately protected Kubernetes Secrets for production.
8. Enable encryption at rest and strict RBAC for stored secrets.
9. Rotate any credential that has been exposed.
10. Never log passwords, Authorization headers or secret values.

Deleting a secret from a file does not make an exposed credential safe. It must be revoked or rotated.

---

## 3. Repository checks

Run these commands from the repository root.

Check the working tree:

```bash
git status --short
```

Check whether `.env` is tracked:

```bash
git ls-files .env
```

Search for tracked environment files:

```bash
git ls-files | grep -E '(^|/)\.env($|\.)'
```

Search for possible private keys and hardcoded credentials:

```bash
git grep -n -I -E 'BEGIN (RSA |OPENSSH )?PRIVATE KEY|password[=: ]+[A-Za-z0-9_!@#$%^&*-]{8,}|secret[=: ]+[A-Za-z0-9_!@#$%^&*-]{8,}' -- ':!*.md'
```

These are quick checks and are not a complete historical secret scan.

If a credential was committed, rotate it and follow the team's repository-history cleanup process.

---

## 4. Application rate limiting

A Redis-backed authentication rate limiter has been implemented in the API.

File:

```text
services/judge-api/src/main/java/org/university/judge/api/security/AuthRateLimitFilter.java
```

Protected endpoints:

```text
POST /api/auth/login
POST /api/auth/register
```

### Configured limits

| Endpoint | Limit | Time window |
|---|---:|---|
| Login | 5 requests | 1 minute |
| Registration | 3 requests | 1 minute |

The limits are currently based on the source IP address obtained from `HttpServletRequest.getRemoteAddr()`.

### Implementation

The filter uses Redis and an atomic Lua script:

```lua
local count = redis.call('INCR', KEYS[1])

if count == 1 then
    redis.call('EXPIRE', KEYS[1], ARGV[1])
end

return count
```

This avoids race conditions when multiple requests arrive simultaneously.

### Exceeded rate limit

When the request count exceeds the configured limit, the API returns:

```http
HTTP/1.1 429 Too Many Requests
Retry-After: 60
Content-Type: application/problem+json
```

Error code:

```text
RATE_LIMIT_EXCEEDED
```

### Redis unavailable

The implementation uses fail-closed behavior.

If Redis is unavailable or the rate-limit operation fails, authentication requests are rejected with:

```http
HTTP/1.1 503 Service Unavailable
```

Error code:

```text
RATE_LIMIT_SERVICE_UNAVAILABLE
```

This prevents requests from bypassing authentication protection when Redis is unavailable.

---

## 5. Local test evidence

The following tests were performed using Swagger against the locally running API.

| Test | Expected result | Actual result |
|---|---|---|
| Sixth login request within one minute | HTTP 429 | Passed |
| Fourth registration request within one minute | HTTP 429 | Passed |
| Login request while Redis is stopped | HTTP 503 | Passed |
| Redis restarted | Redis responds with PONG | Passed |

The Maven `clean verify` command also completed successfully.

These tests verify the local implementation. Production behavior still requires deployment and testing in the actual environment.

---

## 6. Important proxy consideration

The current filter uses:

```java
request.getRemoteAddr()
```

When the API is behind Cloudflare or another reverse proxy, the address may represent the proxy instead of the original client.

DevOps and Security must agree on trusted proxy configuration.

Do not trust arbitrary `X-Forwarded-For` headers from direct clients. Otherwise, an attacker may spoof the source IP and bypass IP-based limits.

A trusted proxy configuration must be tested before production use.

---

## 7. Additional rate-limiting recommendations

The current implementation protects login and registration.

Additional protection should be considered for:

- Submission creation.
- Expensive teacher operations.
- Administrative endpoints.
- Public endpoints that can be abused.
- Repeated requests from authenticated users.

Future limits should consider both authenticated user identity and source IP where appropriate.

Thresholds must be agreed with the Project Manager and DevOps and tested against expected usage.

Avoid account-only lockout mechanisms that allow attackers to lock out legitimate users.

---

## 8. Cloudflare and public ingress

Cloudflare configuration requires coordination with the DevOps owner and account administrator.

Checklist:

- [ ] DNS points to the intended public ingress.
- [ ] HTTPS is enforced.
- [ ] Origin traffic is protected.
- [ ] DDoS protection is enabled.
- [ ] Rate-limit rules protect login and registration.
- [ ] Submission endpoints receive appropriate protection.
- [ ] Normal Portal and result requests are not blocked.
- [ ] Authorization headers reach the API correctly.
- [ ] Direct origin access is restricted where feasible.
- [ ] Sensitive information is removed from logs.
- [ ] False positives are tested.

Cloudflare protection does not replace application-level authentication, authorization or Redis-backed rate limiting.

Feature availability depends on the actual Cloudflare account plan.

---

## 9. Remaining work

- [ ] Confirm trusted proxy configuration.
- [ ] Agree on production rate-limit thresholds.
- [ ] Review rate limits for submission endpoints.
- [ ] Confirm production secret storage.
- [ ] Document secret rotation responsibility.
- [ ] Configure Cloudflare with DevOps.
- [ ] Verify production behavior after deployment.
