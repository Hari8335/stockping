# Shared contracts

`proto/stockping/skeleton/v1/probe.proto` is the only Stage 0 contract. `SkeletonProbe.Check` accepts `ProbeRequest` and returns `ProbeResponse`, each with one string field. It proves Java and Go message/stub generation without defining business RPCs or deploying a server. It is not the HTTP health API.

`stockping.skeleton.v1` is versioned independently of future Catalog and Demand contracts. Preserve protobuf field numbers and reserve deleted fields as these contracts evolve. Internal request ownership/authentication and deadlines belong in the stages that introduce actual service calls.

Run `mvn -B -ntp -pl contracts/java -am test` and `./scripts/generate-proto.sh` from the root. The Java reactor compiles messages and stubs into `contracts/java/target`; the Docker generator writes Go output to `messaging-service/gen`. Go's Dockerfile generates stubs afresh on every source change. Neither generated directory is tracked.

Both language tests serialize `ProbeRequest(message="stockping")` to `0a0973746f636b70696e67`, decode that fixture, and verify the generated method is `stockping.skeleton.v1.SkeletonProbe/Check`. This verifies a shared wire format and stub compilation; runtime cross-service gRPC integration tests will be added with the Stage 1 server and subsequent clients.


## Stage 1 Catalog contract

[stockping/catalog/v1/catalog.proto](proto/stockping/catalog/v1/catalog.proto) defines unary `CatalogService.ResolveProductVariant` and `CatalogService.GetInventory`. Both Java and Go generation include it. Structured product name, brand, SKU and attributes resolve to MATCHED, NOT_FOUND or AMBIGUOUS; exact variant inventory comes only from Catalog. Candidates are bounded to 100 with total_matches supplied. No event or future Demand/Messaging RPC is added.

Stage 2 adds optional exact `variant_id` at field 6 to ResolveProductVariantRequest; existing fields retain their numbers. Exact identity cannot be combined with search criteria. Bearer JWT metadata is required; merchant assertions must match the signed identity. Missing/invalid authentication returns UNAUTHENTICATED, wrong role PERMISSION_DENIED, ownership mismatch/inaccessible inventory NOT_FOUND, and invalid input INVALID_ARGUMENT. Development gRPC is plaintext on the private local network and loopback-published port 9091.

Java integration tests run a generated client against a real TCP server backed by PostgreSQL. The standalone [Go client](../scripts/catalog-grpc-check.go) uses 10-second deadlines to resolve a known SKU and retrieve stock, and is exercised by `python3 scripts/validate-catalog.py` against Compose. The Stage 0 probe wire tests remain unchanged. See the [Catalog guide](../docs/catalog.md) for full semantics and commands.
