#!/bin/sh
set -eu

# No xtrace: credentials must not appear in logs. Configuration lives only in tmpfs.
fail() { echo "Private storage initialization failed; check the supplied configuration and MinIO availability." >&2; exit 1; }
trap 'rm -rf /tmp/mc /tmp/storage-policy.json' EXIT

[ -n "${MINIO_ROOT_USER:-}" ] && [ -n "${MINIO_ROOT_PASSWORD:-}" ] || fail
[ -n "${S3_ACCESS_KEY:-}" ] && [ -n "${S3_SECRET_KEY:-}" ] || fail
[ "$S3_ACCESS_KEY" != "$MINIO_ROOT_USER" ] || fail
[ "${#S3_ACCESS_KEY}" -ge 3 ] && [ "${#S3_SECRET_KEY}" -ge 8 ] || fail
case "${S3_BUCKET:-}" in
  ''|*[!a-z0-9.-]*|.*|*.|-*|*-|*..*|*.-*|*-.*) fail ;;
esac
[ "${#S3_BUCKET}" -ge 3 ] && [ "${#S3_BUCKET}" -le 63 ] || fail
case "$S3_ACCESS_KEY" in *[!a-zA-Z0-9_-]*) fail ;; esac

mc() { command mc --config-dir /tmp/mc "$@" >/dev/null 2>&1 || fail; }
mc alias set local http://minio:9000 "$MINIO_ROOT_USER" "$MINIO_ROOT_PASSWORD"
mc mb --ignore-existing --region "${S3_REGION:-us-east-1}" "local/$S3_BUCKET"
mc anonymous set none "local/$S3_BUCKET"

cat > /tmp/storage-policy.json <<EOF
{
  "Version": "2012-10-17",
  "Statement": [
    {"Effect":"Allow","Action":["s3:ListBucket","s3:GetBucketLocation"],"Resource":["arn:aws:s3:::$S3_BUCKET"]},
    {"Effect":"Allow","Action":["s3:GetObject","s3:PutObject","s3:DeleteObject","s3:AbortMultipartUpload","s3:ListMultipartUploadParts"],"Resource":["arn:aws:s3:::$S3_BUCKET/submissions/*"]}
  ]
}
EOF
mc admin user add local "$S3_ACCESS_KEY" "$S3_SECRET_KEY"
mc admin policy create local "judge-storage-$S3_BUCKET" /tmp/storage-policy.json
mc admin policy attach local "judge-storage-$S3_BUCKET" --user "$S3_ACCESS_KEY"
echo "Private storage bucket and scoped application credentials initialized."
