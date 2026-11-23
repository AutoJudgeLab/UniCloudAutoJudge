# Dedicated Linux worker host

These files deploy the existing Docker-based worker on a separate Linux host.
They do not mount a Docker socket into the API Kubernetes deployment. Kubernetes
API replicas reach the worker through Redis and the authenticated internal HTTP API.

Install Java 25, Docker, the language images from ../docker/worker-toolchains, and
the Maven-built judge-worker-0.1.0-SNAPSHOT-exec.jar as /opt/judge/judge-worker.jar.
Provision a dedicated judge-worker user/group with UID/GID 65532 (or adjust both
the account and mount options to an unused ID). Protect /etc/judge/worker.env with
root ownership and mode 0600. Supply Redis credentials, JUDGE_API_BASE_URL,
JUDGE_WORKER_TOKEN and a unique WORKER_CONSUMER_NAME per worker process.

Install both units into /etc/systemd/system, then enable the mount before starting
judge-worker.service. The mount filename is the systemd-escaped name of
/var/lib/judge-work: use systemd-escape --path --suffix=mount to confirm it.
Check findmnt /var/lib/judge-work before starting the service. It must be the
dedicated 2 GiB tmpfs with 50,000 inodes; a plain directory on the host root disk
does not provide the hard aggregate limit. An equivalently quota-limited dedicated
filesystem may replace the tmpfs on hosts with less RAM. Mount it at the same path
for both the worker and Docker daemon. Docker access gives host-level privileges;
use an isolated worker machine without API/database credentials.

The worker additionally enforces a 512 MiB per-file limit and monitors each compile
workspace against 512 MiB / 10,000 entries. WORKER_WORKSPACE_BYTES and
WORKER_WORKSPACE_FILES configure these per-job budgets. The monitor runs every
100 ms and can overshoot between checks; the dedicated mount is the hard host
protection. Size its aggregate capacity for concurrency plus headroom. A full
workspace fails compilation; it must not consume the host root filesystem.

Before production, exercise service restart with a running evaluation, confirm
heartbeat/reclaim and PostgreSQL recovery, and run the workload/restore procedures
in docs/reliability.md and docs/slo-sla.md. These units are deployment artifacts;
they have not been installed on or validated against a production Linux host.
