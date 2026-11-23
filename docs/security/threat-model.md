# UniCloudAutoJudge — Security Threat Model

**Project:** UniCloudAutoJudge  
**Owner:** Security Engineer  
**Status:** Updated initial threat model; requires review with the development team.

**Primary concern:** Execution of untrusted student-submitted source code.

---

## 1. Assets to protect

1. Host machines and container runtime.
2. PostgreSQL data.
3. Student, teacher and administrator accounts.
4. Assignments and assignment revisions.
5. Student submissions and evaluation results.
6. Redis streams, sessions and pending jobs.
7. MinIO source files and hidden test data.
8. JWT signing keys, Portal client secret and service credentials.
9. Integrity of grades and evaluation results.
10. Availability of the API, Worker, queue and storage.
11. Portal authentication sessions and external identity information.

---

## 2. Trust boundaries

The system contains several security boundaries:

- Student browser to public ingress.
- Public ingress to Judge API.
- Browser session authentication through the Portal identity provider.
- Bearer-token authentication to Judge API.
- Judge API to PostgreSQL.
- Judge API to Redis.
- Judge API to MinIO.
- Redis messages to the trusted Worker.
- Worker to isolated Docker execution containers.
- Worker to Judge API using a separate service credential.
- DevOps/cloud control plane to deployed infrastructure.

Student-submitted source code must always be treated as untrusted.

An authenticated user does not make submitted code safe.

Portal authentication establishes user identity and permissions; it does not make student code trustworthy.

---

## 3. Threats and mitigations

| Threat | Example | Mitigation / current status |
|---|---|---|
| CPU exhaustion | Infinite loop | Docker CPU limit and execution timeout |
| Memory exhaustion | Allocate memory until OOM | Docker memory and swap limits |
| Fork bomb | Spawn processes repeatedly | Docker PID limit and unprivileged user |
| Network access | Connect to external or internal hosts | Sandbox uses `--network none` |
| Filesystem access | Read host files | Read-only root filesystem and restricted workspace mount |
| Privilege escalation | Exploit Linux capabilities | Non-root user, dropped capabilities and `no-new-privileges`; seccomp profile still needs verification |
| Secret theft | Read database credentials | Do not expose API or Worker secrets to student execution containers |
| Hidden-test disclosure | Read expected test output | Keep hidden expected results outside the student execution environment; verify actual implementation |
| Output exhaustion | Produce huge stdout/stderr | Bounded output capture |
| Disk exhaustion | Create many or large files | Workspace size/file-count checks and limited temporary filesystem |
| Queue abuse | Submit excessive jobs | Application rate limiting and bounded Redis stream configuration; production abuse limits need review |
| Unauthorized grading | Submit forged result | Separate Worker service credential and API authorization |
| Replay attacks | Submit an old evaluation result | Attempt IDs, deduplication and delivery controls; verify implementation with tests |
| JWT abuse | Use forged or expired token | Signature, issuer, audience, timestamp and scope validation |
| Portal identity abuse | Use an invalid identity or insufficient permissions | Portal token validation and UserInfo permission checks |
| Session theft | Steal a browser session cookie | HttpOnly, Secure and SameSite=Lax session cookie; Redis-backed sessions |
| Credential leakage | Commit `.env` or private keys | Environment-based secrets; repository scanning and secret rotation still require verification |
| API denial of service | Repeated authentication requests | Redis-backed application rate limiter; edge protection requires deployment verification |
| Vulnerable dependencies | Exploit outdated libraries or images | Dependabot and Trivy scanning require confirmation in the current repository and CI |

---

## 4. Risk priorities

### P0 — Required before public code execution

- Never execute untrusted student code in the API process.
- Never execute untrusted student code directly in the trusted Worker process.
- Use a separate sandbox container for each execution.
- Apply CPU, memory, process and wall-clock limits.
- Disable network access inside the sandbox.
- Restrict filesystem access.
- Prevent access to secrets and host resources.
- Authenticate Worker communication with the API.
- Verify that hidden expected test data cannot be accessed by student code.
- Test sandbox isolation with controlled negative tests.
- Secure Docker daemon access and the Worker host.

### P1 — Additional security hardening

- Application and edge rate limiting.
- Secure secrets storage and rotation.
- Dependabot configuration and dependency updates.
- Trivy image and filesystem scanning.
- Log redaction.
- Audit privileged operations.
- Review Portal SSO integration and token validation.
- Verify seccomp and AppArmor/SELinux protections.
- Monitoring and alerting for suspicious activity.
- Verify container cleanup and host resource recovery.

---

## 5. Existing protections

### API and authentication

The reviewed API implementation includes:

- Spring Security authentication.
- Local RS256 JWT validation.
- Portal OIDC authentication mode.
- Portal access-token validation, including issuer, audience, timestamps and required scopes.
- Portal UserInfo permission checks.
- Role-based access control.
- Additional subject restriction for Portal administrators.
- Redis-backed browser sessions.
- HttpOnly, Secure and SameSite=Lax session cookie settings.
- Environment-based secret configuration.

The API configuration also disables detailed error messages and stack traces in HTTP error responses.

### Worker sandbox

The reviewed `DockerSandboxRunner` implementation includes:

- A fresh Docker container for each execution.
- Network isolation.
- Read-only root filesystem.
- Unprivileged execution user.
- Dropped Linux capabilities.
- `no-new-privileges`.
- CPU, memory and PID limits.
- Restricted temporary filesystem.
- Wall-clock execution timeout.
- Bounded standard output and error capture.
- Workspace size and file-count checks.
- Container cleanup attempts after execution.

Default Worker resource limits are documented in `worker-sandbox.md`.

### Authentication rate limiting

A Redis-backed authentication rate limiter has been implemented and locally tested.

Configured defaults:

- Login: 5 requests per minute per source IP.
- Registration: 3 requests per minute per source IP.

The limiter returns HTTP 429 when a limit is exceeded and HTTP 503 when Redis is unavailable.

These protections do not establish that the entire production infrastructure or sandbox is secure.

---

## 6. Current limitations and assumptions

- Docker sandbox isolation requires testing in the actual deployment environment.
- Seccomp and AppArmor/SELinux enforcement have not been confirmed from the reviewed runner code.
- Docker daemon access remains a critical host-security risk.
- The Worker host must not expose Docker management access to student code.
- Hidden-test isolation must be verified against the actual grading workflow.
- Workspace checks are not equivalent to a dedicated filesystem quota.
- Production image provenance and vulnerability scanning require verification.
- Portal identity-provider configuration and production redirect URLs require review.
- Audience claim format should be verified against actual Portal-issued tokens.
- Production infrastructure and public ingress require confirmation.
- Client IP detection behind trusted proxies must be configured correctly.
- Production rate-limit thresholds require review.
- Dependabot and Trivy workflows must be confirmed in the active repository and tested in CI.

---

## 7. Acceptance evidence

A mitigation is considered verified only when evidence exists.

Acceptable evidence includes:

- Successful positive and negative tests.
- Security scan reports.
- Reviewed configuration.
- Deployment verification.
- Approved integration contracts.
- Documented test results.
- Controlled sandbox escape and resource-exhaustion tests.
- Verification of Docker container cleanup.
- Verification that student code cannot access credentials or hidden expected results.

A design document or source-code comment alone does not prove that a security control works.

Never run destructive security tests against a shared or production host.

Use a disposable, isolated test environment.