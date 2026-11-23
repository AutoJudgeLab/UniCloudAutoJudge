# Private object storage

Stage 7 implements `ObjectStorageService` in judge-storage using the MinIO Java
SDK and its S3-compatible protocol. The module depends on Spring configuration
and validation, with no JPA entities or MVC dependency. judge-api imports
`S3StorageConfiguration` and injects the interface through constructor injection.
Stage 8 uses it from the [submission HTTP workflow](submissions.md); source-download
HTTP endpoints remain deferred.

## Configuration

Spring properties use the `storage.s3` prefix. judge-api maps these environment variables:

| Variable | Default / requirement |
| --- | --- |
| `S3_ENDPOINT` | `http://localhost:9000`; HTTP(S) origin only, without credentials, path, query, or fragment. |
| `S3_ACCESS_KEY` | Required application user access key; separate from MinIO root. |
| `S3_SECRET_KEY` | Required application secret key; no default credentials. |
| `S3_BUCKET` | `judge-private`; valid S3 bucket name. |
| `S3_REGION` | `us-east-1`; must match the server/bucket. Compose also configures the server region. |
| `S3_MAX_UPLOAD_SIZE` | `1MB`; between 1 byte and 64 MiB. Spring data-size units use binary multiples. |
| `S3_ALLOWED_EXTENSIONS` | `java,py,c,cpp,js,ts,kt,go,rs,cs`; lowercase extension allowlist without dots. |
| `S3_REQUEST_TIMEOUT` | `10s`; 1–120 seconds per S3 HTTP request, including each multipart request. |
| `S3_MAX_REQUEST_SIZE` | `2MB`; HTTP multipart envelope limit. Set larger than the upload limit to allow multipart overhead. |

Configuration validation fails startup for missing credentials, invalid endpoint,
invalid bucket, or invalid bounds. Configuration and upload diagnostic strings
redact credentials/content. Storage failures have sanitized messages without SDK
causes, signed request details, or credentials. The API exposes public status-only
Actuator health and ADMIN-protected metrics, never configuration/environment endpoints.
Use HTTPS for remote S3.

Configuration creates the client without network I/O. A bucket must already exist;
operations report failures if storage is unavailable or permissions are insufficient.
Stage 10 includes authenticated storage connectivity in Actuator readiness; the
HEAD probe does not replace actual write validation or capacity monitoring.

## Development bucket initialization

Fill in `.env` with separate root and application credentials. For this development
initializer, access keys contain at least three letters/digits/underscores/hyphens,
and secret keys contain at least eight characters. Generate strong independent
secrets; do not use root credentials as application credentials.

```sh
docker compose --env-file .env -f infrastructure/docker/compose.yml up -d --wait
docker compose --env-file .env -f infrastructure/docker/compose.yml --profile storage-init run --rm --build minio-init
```

MinIO data persists in the existing `minio-data` named volume. The initializer runs
only when requested, after MinIO is healthy. It builds the pinned MinIO client
from source, creates the bucket if missing, removes anonymous bucket access, and
creates/updates the dedicated application user with the supplied credentials.
It attaches a bucket-scoped policy granting object read/write/delete and multipart
cleanup under `submissions/*`, plus bucket listing/location access. It does not
grant policy administration or access to other buckets. Use a dedicated new
application user; this initializer does not audit/remove any unrelated pre-existing
IAM policies attached to a reused user.

Repeated initialization with the same values is idempotent and preserves objects.
Changing credentials and rerunning updates the development user; coordinate API
configuration accordingly. Root and application values are not printed. The
initializer uses a read-only container with temporary configuration in tmpfs and
removes that configuration on exit. Runtime applications receive only application
credentials. Production bucket/IAM provisioning belongs in deployment tooling;
keep the bucket private and grant only the documented permissions.

Export `.env` and configure JWT keys as described in the README. For Maven's run
goal, install reactor dependencies first:

```sh
mvn -pl services/judge-api -am install -DskipTests
mvn -pl services/judge-api spring-boot:run
```

Alternatively, `mvn clean verify` builds the executable API JAR with judge-storage
included; run that JAR with the exported environment.

## Service contract and keys

`upload(SourceUpload)` takes a submission UUID, original filename, exact declared
byte length, and InputStream. It generates a fresh key for every upload:

```text
submissions/{submissionId}/source/{randomUUID}.{validatedExtension}
```

The convenience upload generates its destination key. Stage 8 additionally uses
prepare(filename, length, stream), newSourceKey(submissionId, prepared), and
upload(plannedKey, prepared) so a cleanup intent can commit before the PUT. Prepared
sources expose their SHA-256 fingerprint and must be closed after use. The planned-key
overload is for trusted application orchestration only: always generate a fresh
key per attempt, never accept keys from HTTP, and never reuse an accepted key. Original
filenames never become object paths or temporary filenames. They are preserved
as UTF-8 Base64URL user metadata, decoded by `getMetadata`. Returned metadata
contains objectKey, originalFilename, size, server-selected contentType, and ETag.
ETag is opaque and is not promised to be a checksum.

`download(key)` returns `StoredObject`, holding metadata and an InputStream. Close
the StoredObject with try-with-resources, including when stopping a read early.
`getMetadata(key)` returns metadata without loading object content. `exists(key)`
returns false only for a missing object; storage/bucket/permission failures remain
errors. `delete(key)` is idempotent for a missing object and permanently removes
the named object when allowed by storage policy. Future business services must
authorize keys and enforce submission/history retention before deleting anything.
The storage module itself has no user identity or database access.

All key-based operations reject arbitrary paths, URLs, traversal, and encoded
separators. Keys use canonical UUID segments and generated filenames.
`ObjectKeys.evaluationArtifactPrefix(submissionId, evaluationId)` reserves:

```text
submissions/{submissionId}/evaluations/{evaluationId}/artifacts/
```

Artifact generation/upload workflows are deferred. The upload operation currently
accepts source text only. No public URLs or presigned URLs are returned.

## Upload validation and resource usage

- Reject missing/blank filenames, path separators, traversal, control/format
  characters, invalid extensions, empty content, and whitespace-only content.
- Filename length is capped at 255 characters and 512 UTF-8 bytes. Extension
  matching is case-insensitive; original filename metadata preserves its case.
- Check both declared size and actual bytes. A mismatch or an oversized stream
  fails before any S3 upload begins.
- Ignore client MIME types: the interface accepts none. Validate strict UTF-8
  text, rejecting malformed encoding and binary control bytes. Store it as
  `text/plain; charset=utf-8`. Extension and text checks do not prove that a file
  is syntactically valid source code; compilation belongs to a future isolated worker.
- Stream to a platform-generated temporary file with bounded buffers, validate
  the whole staged file, then upload with 5 MiB multipart buffers. Downloads stream
  directly from S3. Large uploads are never assembled entirely in application memory.
- Temporary files are deleted on success and failure. Supply adequate private
  temporary disk space and clean abandoned files after abnormal process termination.
  The caller owns and must close the upload InputStream.

Files are never extracted, compiled, or executed. Source bytes, whitespace, and
line endings are preserved. Stage 8 registers durable cleanup intents before PUT
and reconciles unreferenced objects, including lost acknowledgements and late writes.
Retries generate fresh keys. MinIO also reclaims incomplete multipart uploads using
MINIO_STALE_UPLOADS_EXPIRY (24h) and MINIO_STALE_UPLOADS_CLEANUP_INTERVAL (1h) in
Compose. Other S3 deployments must configure equivalent incomplete-upload cleanup;
this must not expire completed accepted source objects.

Failure codes are `INVALID_UPLOAD`, `UNSUPPORTED_FILE_TYPE`, `UPLOAD_TOO_LARGE`,
`INVALID_OBJECT_KEY`, `OBJECT_NOT_FOUND`, and `STORAGE_UNAVAILABLE`. The submission
HTTP adapter maps them to sanitized 400/404/413/503 responses.

## Verification

```sh
mvn clean verify
mvn clean verify -Pintegration-tests
```

Unit tests cover configuration, redaction, key/path validation, content validation,
size boundaries, and temporary-file cleanup. `ObjectStorageIT` uses isolated
Testcontainers MinIO, generated credentials, and the actual initialization script.
It covers repeated private initialization, scoped access, upload/download/metadata,
existence, deletion, missing objects, invalid files, size limits, multi-part
streaming, and sanitized storage failures. The first run needs network access to
build the pinned server/client images from source; later runs reuse Docker caches.
All prior PostgreSQL/Redis/API tests remain in the full reactor suite.
