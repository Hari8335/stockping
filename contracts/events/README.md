# Stage 4 JSON events

`inventory.stock_available.v1.schema.json` and `notification.requested.v1.schema.json` define the version 1 wire contracts. UUIDs are canonical, nonzero strings; timestamps are UTC RFC3339. Consumers reject unsupported versions and malformed messages to dead-letter queues. Add a new version for incompatible changes.

Catalog's event ID is the existing stock audit ID. The notification event ID equals its notification job ID; both remain stable on replay. Correlation IDs propagate from the inventory request. Notification events contain only identifiers: phone numbers and current product details are retrieved through authenticated Demand gRPC after eligibility revalidation.

Both event types use durable direct exchange `stockping.events`, persistent publishing, mandatory routing and publisher confirms. See [Stage 4 operations and guarantees](../../docs/stage-4-validation.md) for queue bindings and recovery. The Java records in the contracts module and Go notification decoder enforce these wire shapes; generated protobuf contracts remain the internal synchronous API.
