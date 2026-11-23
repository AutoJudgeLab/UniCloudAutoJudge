# Security

Public registration accepts username/email/password only and creates STUDENT.
BCrypt hashes never appear in DTOs. JWTs use environment-provided RSA keys and
validate issuer, audience, signature, and expiry. Current database membership,
enabled status, and token version are checked on each authentication. Disabling an
account invalidates earlier tokens; re-enabling does not revive them. The last
active administrator cannot be removed or disabled. Bootstrap is idempotent,
requires explicit environment credentials, and must be disabled after provisioning.

Business services enforce roles and ownership, not client-supplied user IDs.
Students do not receive hidden inputs, expected outputs, actual outputs of hidden
tests, source keys, hashes, or credentials. Health endpoints show status only;
metrics require ADMIN JWTs. Documentation is configurable. Production ingress
must provide TLS, request-size limits, rate limiting, and access-log redaction;
these manifests intentionally do not provision an ingress or certificate issuer.

MinIO buckets are private. Use bucket-scoped API credentials created by storage-init,
never root credentials in judge-api. Uploads use generated paths and bounded disk
staging; MIME claims are ignored, UTF-8/extension/size rules are enforced, and
archives are not extracted. Code is never executed by the API.

Redis requires authentication locally. In production use TLS and least-privilege
ACLs on the configured stream; the producer needs XADD, XLEN and health PING,
while consumers use a different service identity. Worker ACLs must allow
XGROUP CREATE, XREADGROUP, XPENDING, XCLAIM, XACK, XADD and
the EVAL/EVALSHA/SCRIPT LOAD operations used for atomic renewal/dead-letter scripts,
restricted to its submission and dead-letter stream keys. The current Compose
deployment uses standalone Redis. Redis Cluster would additionally require both
script keys to share a hash slot and appropriate stream-name configuration.
Separate
metrics and administrative operator access. Do not expose database, Redis,
MinIO admin console, or Kubernetes management ports to untrusted clients.

Console logs use ECS JSON with fixed outcomes/error codes and event IDs. Custom
logs do not record source, request bodies, Authorization headers, passwords, S3
credentials, or message payloads. Hibernate JDBC error-detail logging is disabled
to avoid database diagnostics echoing sensitive row contents. Keep SQL bind and
HTTP wire logging disabled. Collect logs with access controls and retention limits.

Kubernetes Secrets are references, not checked-in values and not encryption by
themselves: enable encryption at rest/RBAC or an external secret manager. Rotate
JWT and storage credentials through a planned rollout. Backups need the same
privacy controls as production. No default password or worker credentials exist.
See [worker transport](worker-transport.md) for the implemented begin, heartbeat
and completion API. Its dedicated service credential grants evaluation access
only; user and Portal tokens cannot call these endpoints.

Portal mode uses OIDC/PKCE and shared Redis HTTP sessions with a 30-minute idle
timeout. Access tokens and OIDC state remain server-side; Redis session data must
be private, authenticated and encrypted in transit in production. Allow session
hash/read/write/expiry operations on judge:portal:sessions:* for the API identity.
The session store uses Spring Session's trusted Java serialization: only the API
may write this namespace. Do not share that Redis credential with other modules.
UserInfo is intentionally checked for each request so role revocation is immediate;
an outage fails closed. Identity synchronization locks only the affected external
identity and leaves unchanged profiles untouched.

Compiler workspaces have file-size, byte and entry budgets. Production workers
also require a dedicated filesystem with a hard aggregate quota; the supplied
[Linux worker units](../infrastructure/systemd/README.md) use a bounded tmpfs.

