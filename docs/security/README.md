# UniCloudAutoJudge — Security Documentation

**Project:** UniCloudAutoJudge  
**Role:** Security Engineer  
**Status:** Week 2 security documentation and implementation review

This directory contains the security requirements, implementation notes, tests and remaining risks for UniCloudAutoJudge.

It covers:
- Authentication and authorization, including UniCloud Portal SSO.
- Secrets management and API rate limiting.
- Worker and student-code sandbox security.
- Threat modeling and security testing.
- Security responsibilities and outstanding work.

## 1. Current security architecture

The project supports two authentication modes:

- **Local mode:** existing local authentication using username/password and RS256 JWTs.
- **Portal mode:** authentication through the shared UniCloud Portal using OpenID Connect (OIDC).

Portal mode uses the authorization code flow with mandatory PKCE (S256). The module is registered as a confidential client, and its client secret is kept on the server. The API validates Portal access tokens and obtains user permissions through the Portal UserInfo endpoint.

The Worker uses a separate service credential. It must not use a student's access token.

The intended architecture separates:
- React frontend.
- Judge API.
- UniCloud Portal identity provider.
- PostgreSQL, Redis and MinIO.
- Trusted Worker.
- Isolated containers for untrusted student-code execution.

## 2. Existing security protections

The reviewed project contains the following security mechanisms:

- Spring Security authentication and authorization.
- Local RS256 JWT validation in local authentication mode.
- OIDC login and Portal access-token validation in Portal mode.
- Portal token issuer, audience, signature, timestamp and scope validation.
- User identity and permission checks through UserInfo.
- Local account and role restrictions.
- Password hashing for local accounts.
- Redis-backed authentication rate limiting.
- Environment-based configuration for credentials.
- Private object-storage configuration.
- Separate Worker service authentication.
- Docker-based execution sandbox implementation.

The exact effectiveness of runtime isolation must be verified through configuration review and security tests. The existence of sandbox code alone does not prove that every security boundary is correctly enforced.

## 3. Implemented security work and requirements

### Authentication and Portal SSO

The project supports local and Portal authentication modes. Portal mode uses the shared identity provider instead of requiring users to create another local password.

Important security requirements:
- Use the registered module's actual client ID and secret.
- Request `openid profile email unicloud`.
- Use authorization code flow and PKCE S256.
- Keep the client secret on the server.
- Validate the token issuer, signature, audience, time claims and required scopes.
- Obtain current permissions through the UserInfo endpoint.
- Never expose Portal tokens or secrets to frontend JavaScript.
- Keep Worker authentication separate from student authentication.

**Verification still required:** confirm the actual Portal access-token audience format in staging. The current validator expects the audience claim as a list of strings.

See `../portal-sso.md` and `jwt-integration.md` for integration details.

### API rate limiting

A Redis-backed rate limiter protects local authentication endpoints:

| Endpoint | Limit |
|---|---:|
| Login | 5 requests per minute per source IP |
| Registration | 3 requests per minute per source IP |

The implementation uses Redis and an atomic Lua script.

When a limit is exceeded, the API returns HTTP `429 Too Many Requests`.

When Redis is unavailable, the filter returns HTTP `503 Service Unavailable` instead of bypassing the protection.

The limits and time window are configurable. Production values and trusted-proxy client IP handling still require review.

### Secrets management

Security rules:
- Do not commit `.env` files, passwords, private keys or client secrets.
- Keep `.env.example` limited to placeholders.
- Do not expose Portal client secrets to the frontend.
- Do not pass database, Redis, MinIO or signing credentials to student-code containers.
- Use protected environment configuration or an approved secrets manager.
- Rotate any credential that has been exposed.

### Worker and sandbox security

The project contains a Worker implementation and a Docker-based sandbox runner.

The security boundary must remain clear:
- The Worker is trusted application code.
- Student-submitted source code is untrusted.
- Each execution must be isolated from the Worker, API and other submissions.
- Sandbox resource, filesystem, process and network restrictions must be checked.
- Secrets and host resources must never be exposed to student code.

The reviewed runner includes controls such as network isolation, memory and CPU limits, process limits, a read-only container filesystem, dropped Linux capabilities and `no-new-privileges`.

**Limitations requiring further verification:**
- Explicit seccomp, AppArmor and SELinux configuration is not confirmed in the reviewed runner code.
- Periodic workspace checks are not equivalent to an enforced aggregate filesystem quota.
- Docker daemon access remains a significant host-level security boundary.

See `worker-sandbox.md` for the implementation details, limitations and verification checklist.

## 4. Security tests and verification

The repository contains tests related to authentication, Portal configuration and login, rate limiting, Worker transport and sandbox execution.

Tests in the repository are not automatically proof that the complete deployed system is secure. Results must be recorded after running the relevant test suite in the current environment.

Previously performed local rate-limiter tests:

| Test | Observed result |
|---|---|
| Login limit exceeded | HTTP 429 |
| Registration limit exceeded | HTTP 429 |
| Redis unavailable | HTTP 503 |
| Redis restarted | Redis returned PONG |

The previous local Maven verification also completed successfully. These results refer to the earlier tested project state and must be rechecked against the current ZIP before being reported as this week's test results.

## 5. Security documentation

| File | Purpose |
|---|---|
| `README.md` | Main security overview |
| `jwt-integration.md` | Local JWT and external identity integration |
| `secrets-rate-limiting.md` | Secrets, rate limiting and Cloudflare considerations |
| `threat-model.md` | Threats, risks and mitigations |
| `worker-sandbox.md` | Worker and sandbox security requirements |

The Portal integration guide is located at `docs/portal-sso.md`.

## 6. GitHub security configuration

The security configuration should include:

- `.github/dependabot.yml` — dependency update configuration.
- `.github/workflows/security-scan.yml` — Trivy filesystem and container image scanning.

These files must be present in the active repository and successfully executed through GitHub Actions before their operation is reported as verified.

## 7. Outstanding security tasks

- [ ] Review Portal SSO configuration against the shared staging environment.
- [ ] Confirm the registered module credentials and callback URL are configured securely.
- [ ] Verify authentication and authorization behavior in both modes.
- [ ] Confirm the actual Portal access-token audience format.
- [ ] Review existing-user mapping and migration requirements before any production cutover.
- [ ] Review sandbox Docker settings and verify isolation limits.
- [ ] Run controlled negative tests against the sandbox in a disposable environment.
- [ ] Confirm secrets are not exposed to Worker or sandbox processes.
- [ ] Review rate-limit settings and trusted-proxy configuration with DevOps.
- [ ] Confirm production secrets storage and rotation.
- [ ] Restore and verify Dependabot and Trivy workflows.
- [ ] Review security scan findings and document unresolved risks.

## 8. Responsibilities

| Area | Responsibility |
|---|---|
| Security requirements and threat model | Security Engineer |
| Security documentation and review | Security Engineer |
| Authentication and authorization implementation | API developers, with Security review |
| Worker implementation | Worker developer |
| Docker host and deployment configuration | DevOps |
| Sandbox security review and negative tests | Security Engineer with Worker developer and DevOps |

## 9. Security principle

All student-submitted code must be treated as untrusted.

Authentication does not make submitted code safe. The API, trusted Worker and isolated execution sandbox must remain separate security boundaries.

A security control is considered verified only when its implementation, configuration or test evidence has been reviewed.
