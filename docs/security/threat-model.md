
# UniCloudAutoJudge — Security Threat Model

**Project:** UniCloudAutoJudge  
**Owner:** Security Engineer  
**Status:** Initial threat model; requires review with the development team.

**Primary concern:** Execution of untrusted student-submitted source code.

---

## 1. Assets to protect

1. Host machines and container runtime.
2. PostgreSQL data.
3. Student, teacher and administrator accounts.
4. Assignments and assignment revisions.
5. Student submissions and evaluation results.
6. Redis streams and pending jobs.
7. MinIO source files and hidden test data.
8. JWT signing keys and service credentials.
9. Integrity of grades and evaluation results.
10. Availability of the API, worker, queue and storage.

---

## 2. Trust boundaries

The system contains several security boundaries:

- Student browser to public ingress.
- Public ingress to Judge API.
- Judge API to PostgreSQL.
- Judge API to Redis.
- Judge API to MinIO.
- Redis messages to trusted worker.
- Trusted worker to isolated execution sandbox.
- Identity provider to Judge API.
- DevOps/cloud control plane to deployed infrastructure.

Student code must always be treated as untrusted.

A user being authenticated does not make submitted code safe.

---

## 3. Threats and mitigations

| Threat | Example | Required mitigation |
|---|---|---|
| CPU exhaustion | Infinite loop | CPU quota and wall-clock timeout |
| Memory exhaustion | Allocate memory until OOM | Per-sandbox memory limit |
| Fork bomb | Spawn processes repeatedly | PID limit and non-root user |
| Network access | Connect to external or internal hosts | Disable sandbox network |
| Filesystem access | Read host files | Read-only root filesystem and minimal mounts |
| Privilege escalation | Exploit Linux capabilities | Non-root, drop capabilities and seccomp |
| Secret theft | Read database credentials | Never expose secrets to sandbox |
| Hidden-test disclosure | Read expected test output | Keep hidden expected results outside sandbox |
| Output exhaustion | Produce huge stdout/stderr | Output size limits |
| Disk exhaustion | Create many or large files | Workspace quota and cleanup |
| Queue abuse | Submit excessive jobs | Rate limiting and bounded queue policy |
| Unauthorized grading | Submit forged result | Worker identity and authorization |
| Replay attacks | Submit an old evaluation result | Attempt IDs and deduplication |
| JWT abuse | Use forged or expired token | Signature and claim validation |
| Credential leakage | Commit `.env` | Secret scanning and credential rotation |
| API denial of service | Repeated requests | Cloudflare and application rate limiting |
| Vulnerable dependencies | Exploit outdated libraries | Dependabot and Trivy |

---

## 4. Risk priorities

### P0 — Required before public code execution

- Never execute untrusted student code in the API process.
- Never execute untrusted student code in the trusted worker process.
- Use a separate sandbox for each execution attempt.
- Apply CPU, memory, process and wall-clock limits.
- Disable network access inside the sandbox.
- Restrict filesystem access.
- Prevent access to secrets and host resources.
- Authenticate worker result submission.
- Test sandbox isolation with controlled negative tests.

### P1 — Additional security hardening

- Application and edge rate limiting.
- Secure secrets storage and rotation.
- Dependabot configuration.
- Trivy scanning.
- Log redaction.
- Audit privileged operations.
- External JWT integration agreement.
- Monitoring and alerting for suspicious activity.

---

## 5. Existing protections

The reviewed API contains:

- Spring Security authentication.
- RS256 JWT validation.
- BCrypt password hashing.
- Local account and role validation.
- Non-root API Docker configuration.
- Read-only API container configuration.
- Dropped Linux capabilities.
- CPU and memory limits.
- Environment-based secret configuration.

A Redis-backed authentication rate limiter has also been implemented and locally tested.

Configured limits:

- Login: 5 requests per minute per source IP.
- Registration: 3 requests per minute per source IP.

The limiter returns HTTP 429 when a limit is exceeded and HTTP 503 when Redis is unavailable.

These protections do not establish that the worker sandbox is secure.

---

## 6. Current limitations and assumptions

- The worker execution environment must be reviewed and tested separately.
- Final execution languages and resource profiles require agreement.
- The final sandbox runtime must be selected with DevOps.
- Production infrastructure and public ingress require confirmation.
- External identity-provider details must be agreed.
- Production rate-limit thresholds must be reviewed.
- Client IP detection behind trusted proxies must be configured correctly.

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

A design document alone does not prove that a security control works.

Never run destructive security tests against a shared or production host.

Use a disposable, isolated test environment.
