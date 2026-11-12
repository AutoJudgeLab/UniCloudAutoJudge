FROM golang:1.25-alpine AS builder
RUN CGO_ENABLED=0 go install -trimpath -ldflags="-s -w" \
    github.com/minio/mc@RELEASE.2025-08-13T08-35-41Z

FROM alpine:3.22
RUN apk add --no-cache ca-certificates
COPY --from=builder /go/bin/mc /usr/local/bin/mc
ENTRYPOINT ["mc"]
