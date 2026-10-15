#!/bin/sh
set -eu
root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
docker build --target tools -t stockping-proto-tools -f "$root/messaging-service/Dockerfile" "$root"
docker run --rm --user "$(id -u):$(id -g)" \
  -v "$root:/workspace" -w /workspace stockping-proto-tools \
  sh -eu -c 'mkdir -p messaging-service/gen
    protoc -I contracts/proto \
      --go_out=messaging-service/gen --go_opt=paths=source_relative \
      --go-grpc_out=messaging-service/gen --go-grpc_opt=paths=source_relative \
      contracts/proto/stockping/skeleton/v1/probe.proto'

