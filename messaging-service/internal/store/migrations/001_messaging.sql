CREATE TABLE webhook_events (
    id uuid PRIMARY KEY,
    provider_event_id varchar(700) NOT NULL,
    merchant_id uuid NOT NULL,
    event_type varchar(32) NOT NULL CHECK (event_type IN ('INBOUND','DELIVERY')),
    received_at timestamptz NOT NULL DEFAULT now(),
    processed_at timestamptz,
    status varchar(40) NOT NULL,
    error_code varchar(64),
    UNIQUE (merchant_id, provider_event_id)
);
CREATE TABLE message_records (
    id uuid PRIMARY KEY,
    merchant_id uuid NOT NULL,
    webhook_event_id uuid NOT NULL REFERENCES webhook_events(id),
    customer_phone varchar(16) NOT NULL CHECK (customer_phone ~ '^\+[1-9][0-9]{7,14}$'),
    direction varchar(8) NOT NULL CHECK (direction IN ('INBOUND','OUTBOUND')),
    purpose varchar(64) NOT NULL,
    provider_message_id varchar(512),
    status varchar(40) NOT NULL,
    error_code varchar(64),
    correlation_id uuid NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (merchant_id, direction, provider_message_id),
    UNIQUE (webhook_event_id, direction)
);
CREATE TABLE delivery_events (
    id uuid PRIMARY KEY,
    merchant_id uuid NOT NULL,
    webhook_event_id uuid NOT NULL UNIQUE REFERENCES webhook_events(id),
    message_record_id uuid REFERENCES message_records(id),
    provider_message_id varchar(512) NOT NULL,
    provider_status varchar(16) NOT NULL CHECK (provider_status IN ('sent','delivered','read','failed')),
    event_time timestamptz NOT NULL,
    raw_reference varchar(32), -- numeric provider error code only
    UNIQUE (merchant_id, provider_message_id, provider_status, event_time)
);
CREATE INDEX delivery_message_time ON delivery_events(merchant_id, provider_message_id, event_time);
CREATE INDEX message_correlation ON message_records(correlation_id);
CREATE INDEX webhook_processing ON webhook_events(status, received_at);
