
# UniCloudAutoJudge — Security Documentation

**Project:** AutoJudgeLab / UniCloudAutoJudge  
**Team:** 3 — Automatic Grading System  
**Owner:** Security Engineer  
**Status:** Week 1 security documentation and implementation review

This directory contains the central security documentation for the project.

It covers:
- Existing API authentication and authorization.
- JWT integration with other project services and teams.
- Secrets management.
- API rate limiting and abuse protection.
- Worker and sandbox isolation.
- Threat modeling.
- Security verification and outstanding risks.

GitHub security configuration files remain in their standard locations:
- `.github/dependabot.yml`
- `.github/workflows/security-scan.yml`

---

## 1. Current security baseline

The reviewed project contains the following protections:

- Spring Security with stateless Bearer-token authentication.
- Locally issued RS256 JWTs using environment-provided RSA keys.
- JWT validation for signature, issuer, audience, expiry and issued-at time.
- A `LocalUserJwtConverter` that resolves the local user from PostgreSQL.
- Account enabled-status and token-version checks.
- Authorities derived from the local database role.
- BCrypt password hashing.
- API Docker image running as a non-root user (UID/GID 10001).
- Read-only API container configuration.
- Dropped Linux capabilities and `no-new-privileges`.
- CPU and memory limits in the API Compose configuration.
- Environment-based configuration for credentials.
- Private object-storage bucket design and application-scoped credentials.

These protections apply to the existing API and application.

They do not prove that untrusted student code execution is secure.

---

## 2. Implemented Security work

### Authentication rate limiting

A Redis-backed `AuthRateLimitFilter` was added to protect:

- `POST /api/auth/login`
- `POST /api/auth/register`

Current limits:

| Endpoint | Limit |
|---|---:|
| Login | 5 requests per minute per source IP |
| Registration | 3 requests per minute per source IP |

The implementation uses an atomic Redis Lua script with `INCR` and `EXPIRE`.

When the limit is exceeded:
- HTTP status: `429 Too Many Requests`
- Error code: `RATE_LIMIT_EXCEEDED`
- `Retry-After: 60`

When Redis is unavailable:
- HTTP status: `503 Service Unavailable`
- Error code: `RATE_LIMIT_SERVICE_UNAVAILABLE`

The filter fails closed instead of allowing authentication requests to bypass protection.

### Local verification results

The following tests were performed against the locally running API:

| Test | Result |
|---|---|
| Login limit exceeded | Passed — HTTP 429 |
| Registration limit exceeded | Passed — HTTP 429 |
| Redis unavailable | Passed — HTTP 503 |
| Redis restarted | Passed — Redis returned PONG |
| Maven clean verify | Passed |

These are local test results, not evidence of production deployment.

---

## 3. Important outstanding security component

The student-code execution worker and sandbox must be treated as a separate security boundary.

The trusted worker must not execute untrusted student code inside its own process or container.

Each submission must execute inside a separate, short-lived sandbox with strict resource and permission limits.

See [worker-sandbox.md](worker-sandbox.md).

---

## 4. Security documentation

| File | Purpose |
|---|---|
| `threat-model.md` | Security threats, risks and mitigations |
| `worker-sandbox.md` | Requirements for safe execution of student code |
| `jwt-integration.md` | Shared JWT identity integration requirements |
| `secrets-rate-limiting.md` | Secrets, API abuse protection and Cloudflare |
| `README.md` | Main security documentation index |

---

## 5. GitHub security configuration

The repository includes the following security configuration files:

- `.github/dependabot.yml` — dependency update configuration.
- `.github/workflows/security-scan.yml` — Trivy filesystem and API image scanning.

The configurations must be validated through GitHub Actions after being added to the repository.

A configuration file alone does not prove that a scan has successfully executed.

---

## 6. Outstanding tasks

- [ ] Review security documentation with the development team.
- [ ] Agree on sandbox architecture with the worker developer and DevOps.
- [ ] Implement and test worker isolation.
- [ ] Confirm production secrets storage and rotation.
- [ ] Review trusted proxy configuration for client IP detection.
- [ ] Agree on production rate-limit thresholds.
- [ ] Configure Cloudflare protections with DevOps.
- [ ] Verify Dependabot operation on GitHub.
- [ ] Run and review Trivy scans on GitHub.
- [ ] Agree on the JWT identity integration contract with the relevant teams.
- [ ] Perform security testing in a disposable environment.

A task should be marked complete only when there is test evidence, configuration evidence, a reviewed pull request or a written agreement.

---

## 7. Security principle

The system must assume that student-submitted source code is malicious until proven otherwise.

Authentication does not make submitted code trustworthy.

The API, trusted worker and untrusted execution sandbox must remain separate security boundaries.
