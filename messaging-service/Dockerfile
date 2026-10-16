FROM golang:1.26.8-bookworm AS tools
ARG TARGETARCH
RUN case "$TARGETARCH" in arm64) arch=aarch_64 ;; amd64) arch=x86_64 ;; *) exit 1 ;; esac \
    && curl -fsSL "https://repo.maven.apache.org/maven2/com/google/protobuf/protoc/4.36.2/protoc-4.36.2-linux-${arch}.exe" -o /usr/local/bin/protoc \
    && chmod +x /usr/local/bin/protoc
RUN go install google.golang.org/protobuf/cmd/protoc-gen-go@v1.36.12 \
    && go install google.golang.org/grpc/cmd/protoc-gen-go-grpc@v1.6.2

FROM tools AS build
WORKDIR /workspace/messaging-service
COPY messaging-service/go.mod messaging-service/go.sum ./
RUN go mod download
COPY contracts/proto /workspace/contracts/proto
COPY messaging-service .
RUN mkdir -p gen && protoc -I /workspace/contracts/proto \
    --go_out=gen --go_opt=paths=source_relative \
    --go-grpc_out=gen --go-grpc_opt=paths=source_relative \
    /workspace/contracts/proto/stockping/skeleton/v1/probe.proto \
    /workspace/contracts/proto/stockping/catalog/v1/catalog.proto
RUN test -z "$(gofmt -l cmd internal)" && go vet ./... && go test -race ./... \
    && CGO_ENABLED=0 go build -trimpath -o /out/messaging ./cmd/messaging

FROM alpine:3.23
RUN apk add --no-cache ca-certificates && addgroup -S app && adduser -S app -G app
COPY --from=build /out/messaging /usr/local/bin/messaging
USER app
EXPOSE 8083
ENTRYPOINT ["messaging"]

