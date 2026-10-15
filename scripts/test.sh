#!/bin/sh
set -eu
root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
cd "$root"
mvn -B -ntp verify
(cd frontend && npm ci && npm test && npm run build)
./scripts/generate-proto.sh
docker run --rm --user "$(id -u):$(id -g)" \
  -e GOCACHE=/tmp/go-build -e GOPATH=/tmp/go \
  -v "$root:/workspace" -w /workspace/messaging-service stockping-proto-tools \
  sh -eu -c 'test -z "$(gofmt -l cmd internal)"
    go vet ./...
    go test -race ./...
    go build -trimpath -o /tmp/messaging ./cmd/messaging'

