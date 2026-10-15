# Shared contracts

`proto/stockping/skeleton/v1/probe.proto` is the only Stage 0 contract. `SkeletonProbe.Check` accepts `ProbeRequest` and returns `ProbeResponse`, each with one string field. It proves Java and Go message/stub generation without defining business RPCs or deploying a server. It is not the HTTP health API.

`stockping.skeleton.v1` is versioned independently of future Catalog and Demand contracts. Preserve protobuf field numbers and reserve deleted fields as these contracts evolve. Internal request ownership/authentication and deadlines belong in the stages that introduce actual service calls.

Run `mvn -B -ntp -pl contracts/java -am test` and `./scripts/generate-proto.sh` from the root. The Java reactor compiles messages and stubs into `contracts/java/target`; the Docker generator writes Go output to `messaging-service/gen`. Go's Dockerfile generates stubs afresh on every source change. Neither generated directory is tracked.

Both language tests serialize `ProbeRequest(message="stockping")` to `0a0973746f636b70696e67`, decode that fixture, and verify the generated method is `stockping.skeleton.v1.SkeletonProbe/Check`. This verifies a shared wire format and stub compilation; runtime cross-service gRPC integration tests will be added with the Stage 1 server and subsequent clients.

