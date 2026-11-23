# Worker Sandbox Security

## 1. Purpose

The Worker executes source code submitted by students. Since submitted code is untrusted, every execution must run in an isolated environment with restricted resources and permissions.

The project uses Docker containers to isolate compilation and program execution.

## 2. Container isolation

The `DockerSandboxRunner` creates a new temporary Docker container for each execution.

The container uses these restrictions:

| Protection | Implementation |
|---|---|
| Network isolation | `--network none` |
| Read-only root filesystem | `--read-only` |
| Non-root user | Runs as an unprivileged user |
| Linux capabilities | `--cap-drop ALL` |
| Privilege escalation | `no-new-privileges` |
| CPU restriction | Configurable CPU limit |
| Memory restriction | Hard memory limit; swap limited to the same amount |
| Process restriction | Configurable PID limit |
| Temporary storage | Limited `/tmp` with `noexec` and `nosuid` |
| Core dumps | Disabled using `ulimit core=0` |

The student workspace is mounted at `/work`. It is read-only during execution and writable when compilation requires output files.

## 3. Resource limits

Default values from the Worker configuration:

| Resource | Default limit |
|---|---:|
| Program execution time | 5 seconds |
| Program memory | 256 MB |
| Compilation time | 30 seconds |
| Compilation memory | 512 MB |
| CPU | 1 CPU |
| Processes | 128 |
| Temporary filesystem | 64 MB |
| Workspace size | 512 MB |
| Workspace files | 10,000 |
| Captured standard output | 1 MB |
| Captured standard error | 64 KB |
| Stored output | 16 KB |

These values can be changed through environment variables.

The Worker checks workspace usage periodically during compilation. This check is separate from the Docker memory and CPU limits.

## 4. Execution timeout and cleanup

The runner measures execution time using a monotonic clock.

If a program exceeds its time limit, produces too much output, or exceeds the workspace limit, the runner attempts to stop it.

After execution, the runner attempts to remove the temporary container, including when execution is interrupted.

Docker management commands have a timeout so that cleanup operations do not wait indefinitely.

## 5. Docker image security

The Worker supports configured images for Java, Python, C, C++, JavaScript, TypeScript, Kotlin, Go, Rust and C#.

Containers use `--pull never`, meaning images must already be available locally. This prevents the sandbox from downloading images while executing student code.

Docker image versions should be reviewed and updated regularly. Production deployments should use trusted images and preferably pin them to immutable digests.

## 6. Worker and Docker host security

Access to the Docker daemon is highly privileged and can provide control over the host.

The Worker must therefore be deployed carefully:

- Do not mount `/var/run/docker.sock` into a container that executes student code.
- Prefer running the Worker directly on the host with a restricted account, or use rootless Docker/Podman where supported.
- Keep the Docker daemon and host operating system updated.
- Restrict access to the Worker and its configuration.
- Keep the Worker API token secret.
- Do not expose Docker management access to students.

The Worker configuration redacts its API token in the `toString()` output.

## 7. Important security limitations

The current implementation provides several useful isolation controls, but these controls do not prove complete protection against every container escape or host-level attack.

Additional production verification is required for:

- Docker daemon and host permissions.
- Seccomp and AppArmor/SELinux profiles.
- Container image provenance and vulnerability scanning.
- Actual CPU, memory, PID and filesystem enforcement.
- Workspace cleanup and permissions on the production host.
- Container cleanup after Docker daemon failures or forced Worker termination.

The workspace limit is monitored by the Worker; it is not itself a dedicated filesystem quota.

## 8. Responsibilities

**Security Engineer**
- Review sandbox security settings.
- Document security controls and limitations.
- Identify risks and recommend improvements.
- Coordinate security verification with the Worker and DevOps teams.

**Worker Developer**
- Maintain execution and compilation logic.
- Maintain Docker container lifecycle and cleanup.
- Fix issues in the Worker implementation.

**DevOps**
- Secure the Docker host and daemon.
- Configure deployment permissions and isolation.
- Maintain the production environment and monitoring.

## 9. Verification status

The controls described above are based on the supplied Worker source code and configuration.

They should not be considered production-tested until the relevant tests and deployment checks have been completed.