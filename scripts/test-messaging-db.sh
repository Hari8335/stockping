#!/bin/sh
set -eu
root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
name="stockping-messaging-test-$$"
cleanup() { docker rm -fv "$name" >/dev/null 2>&1 || true; }
trap cleanup EXIT INT TERM
# Disposable PostgreSQL, no host ports, no production volumes or provider credentials.
docker run -d --name "$name" -e POSTGRES_DB=messaging_db -e POSTGRES_USER=messaging_user -e POSTGRES_PASSWORD=integration-test-only postgres:18-alpine >/dev/null
n=0
until docker exec "$name" pg_isready -U messaging_user -d messaging_db >/dev/null 2>&1; do
 n=$((n+1)); if [ "$n" -ge 30 ]; then exit 1; fi; sleep 1
done
docker run --rm --network "container:$name" --user "$(id -u):$(id -g)" \
 -e GOCACHE=/tmp/go-build -e GOPATH=/tmp/go \
 -e TEST_DATABASE_URL='postgres://messaging_user:integration-test-only@127.0.0.1:5432/messaging_db?sslmode=disable' \
 -v "$root:/workspace" -w /workspace/messaging-service stockping-proto-tools \
 go test -race -tags integration -count=1 -v ./internal/store ./internal/http
