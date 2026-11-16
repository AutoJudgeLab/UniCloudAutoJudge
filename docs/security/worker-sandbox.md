
# Worker and Per-Submission Sandbox Security

**Project:** UniCloudAutoJudge  
**Owner:** Security Engineer  
**Status:** Security design requirements; implementation and isolation tests remain outstanding.

---

## 1. Purpose

The Judge system executes source code submitted by students.

Student-submitted code must be considered potentially malicious, even when the student is authenticated and the code compiles successfully.

The worker and the execution sandbox must be separate security boundaries.

---

## 2. Separate the worker from untrusted code

The trusted worker is responsible for orchestration.

Its responsibilities include:

1. Receiving a job from Redis.
2. Retrieving the correct submission.
3. Retrieving the immutable assignment revision.
4. Starting an isolated sandbox.
5. Passing the required source files and test input.
6. Collecting bounded output.
7. Comparing the result with expected output.
8. Reporting the evaluation result through an authorized API boundary.
9. Cleaning up the sandbox.

The student's program must execute in a separate, short-lived sandbox.

It must not execute:

- Inside the Judge API process.
- Inside the worker JVM.
- Inside the trusted worker container.

---

## 3. Network separation

The trusted worker needs controlled access to the services required for processing jobs, such as Redis and source storage.

The untrusted student program must have no network access.

Therefore:

- Do not automatically disable networking on the trusted worker if it needs Redis or storage.
- Disable networking for the individual execution sandbox.
- Do not use host networking for the sandbox.
- Do not allow access to internal services or cloud metadata endpoints.

---

## 4. Required sandbox controls

Every execution attempt should have the following protections:

### User and privileges

- Run as a dedicated non-root UID/GID.
- Drop all Linux capabilities.
- Enable `no-new-privileges`.
- Do not allow privilege escalation.

### Filesystem

- Use a read-only root filesystem.
- Provide only a dedicated writable temporary workspace.
- Limit workspace size.
- Do not mount host directories unnecessarily.
- Never mount sensitive host paths.
- Do not expose another student's files.

### Process isolation

- Apply a PID/process limit.
- Apply CPU limits.
- Apply memory limits.
- Enforce a wall-clock timeout.
- Terminate execution when the timeout is exceeded.

### Network isolation

- Disable network access.
- Do not use host networking.
- Block access to internal infrastructure.
- Prevent access to external services.

### Runtime isolation

- Use Docker's default seccomp profile at minimum.
- Evaluate a stricter seccomp profile.
- Consider gVisor or a microVM for stronger isolation.
- Have DevOps review host runtime configuration.

### Output and storage limits

- Limit stdout and stderr.
- Limit the number of generated files.
- Limit generated file sizes.
- Clean up temporary files after execution.

### Secrets

The sandbox must never receive:

- Database credentials.
- Redis credentials.
- MinIO credentials.
- JWT signing keys.
- Student access tokens.
- Production service tokens.
- Host secrets.

---

## 5. Docker daemon warning

Access to the Docker daemon socket, such as:

```text
/var/run/docker.sock
```

can provide powerful control over the host and its containers.

Do not mount the host Docker socket into a general-purpose worker and assume the sandbox is secure.

The team must choose a controlled execution architecture.

Possible approaches include:

- A dedicated sandbox host or agent.
- An isolated execution service with authenticated job requests.
- gVisor.
- A microVM-based runtime.

Host runtime configuration and infrastructure deployment must be coordinated with DevOps.

---

## 6. Worker permissions

The trusted worker must follow least-privilege principles.

- Use a separate worker service identity.
- Read only the required queue messages.
- Retrieve only the source object for the authorized submission.
- Never use MinIO root credentials.
- Never use database administrator credentials.
- Use a narrowly scoped identity when reporting evaluation results.
- Use stable attempt IDs.
- Reject stale or duplicated evaluation results.
- Do not acknowledge queue messages before processing state is safe.
- Record relevant security events.

Logs must not contain:

- Source code.
- Passwords.
- Access tokens.
- Secret values.
- Hidden test contents.

---

## 7. Required negative tests

These tests must be performed only in a disposable isolated environment.

| Test | Expected result |
|---|---|
| Infinite loop | Execution is terminated at the deadline |
| Excessive memory allocation | Memory limit stops the process |
| Fork bomb | PID limit prevents uncontrolled process creation |
| Outbound connection | Network connection fails |
| Write to root filesystem | Write is denied |
| Read host files | Host files are inaccessible |
| Read another submission | Access is denied |
| Read environment secrets | No secrets are available |
| Excessive stdout/stderr | Output is capped |
| Excessive generated files | Workspace limits are enforced |
| Sandbox cleanup | Sandbox is destroyed after execution |
| Forged worker result | API rejects unauthorized result |
| Stale attempt result | API rejects outdated result |

A test should be marked successful only after the actual behavior is observed.

---

## 8. Current repository status

The worker implementation must be checked against the latest repository state.

In the earlier reviewed snapshot, `services/judge-worker` contained only its Maven POM and empty source placeholders.

No complete execution engine or per-submission sandbox was present in that snapshot.

This document therefore defines security requirements. It does not claim that sandbox isolation has already been implemented.

---

## 9. Responsibilities

| Area | Primary responsibility |
|---|---|
| Sandbox security requirements | Security Engineer |
| Worker implementation | Software Engineer responsible for worker |
| Container runtime and host configuration | DevOps |
| Security review and negative tests | Security Engineer + Worker developer |
| Deployment and monitoring | DevOps |
| Final acceptance | Project team |

Security must review the design and verify isolation, but the worker and infrastructure owners must implement their respective components.

---

## 10. Definition of Done

The sandbox security work is complete only when:

- [ ] A separate sandbox is created for each execution attempt.
- [ ] Student code never runs inside the trusted worker.
- [ ] Sandbox runs as non-root.
- [ ] Root filesystem is read-only.
- [ ] Writable workspace is restricted.
- [ ] Network is disabled.
- [ ] CPU and memory limits are enforced.
- [ ] Wall-clock timeout is enforced.
- [ ] PID limit is enforced.
- [ ] Output and storage limits are enforced.
- [ ] No secrets are exposed.
- [ ] Host and other submissions are inaccessible.
- [ ] Sandbox cleanup is verified.
- [ ] Negative tests pass.
- [ ] Security review is documented.
