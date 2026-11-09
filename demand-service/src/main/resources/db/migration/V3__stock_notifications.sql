CREATE TABLE availability_events (
    id uuid PRIMARY KEY,
    merchant_id uuid NOT NULL,
    variant_id uuid NOT NULL,
    payload jsonb NOT NULL,
    outcome varchar(24) NOT NULL CHECK (outcome IN ('PROCESSED','SKIPPED')),
    processed_at timestamptz NOT NULL DEFAULT now()
);
ALTER TABLE waitlist_requests ADD CONSTRAINT uq_waitlist_tenant_customer UNIQUE (merchant_id, customer_id, id);
CREATE TABLE notification_jobs (
    id uuid PRIMARY KEY,
    availability_event_id uuid NOT NULL REFERENCES availability_events(id),
    merchant_id uuid NOT NULL REFERENCES merchants(id),
    variant_id uuid NOT NULL,
    customer_id uuid NOT NULL,
    waitlist_id uuid NOT NULL,
    correlation_id uuid NOT NULL,
    status varchar(24) NOT NULL CHECK (status IN ('QUEUED','AUTHORIZED','ACCEPTED','FAILED','UNKNOWN','SKIPPED')),
    reason varchar(64),
    provider_message_id varchar(512),
    created_at timestamptz NOT NULL DEFAULT now(),
    authorized_at timestamptz,
    completed_at timestamptz,
    UNIQUE (availability_event_id, customer_id),
    UNIQUE (availability_event_id, waitlist_id),
    FOREIGN KEY (merchant_id, customer_id, waitlist_id) REFERENCES waitlist_requests(merchant_id, customer_id, id)
);
CREATE INDEX ix_notification_merchant ON notification_jobs(merchant_id, created_at DESC);
CREATE TABLE event_outbox (
    id uuid PRIMARY KEY REFERENCES notification_jobs(id),
    routing_key varchar(80) NOT NULL,
    payload jsonb NOT NULL,
    status varchar(16) NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING','PUBLISHED','PARKED')),
    attempts integer NOT NULL DEFAULT 0 CHECK (attempts >= 0),
    next_attempt_at timestamptz NOT NULL DEFAULT now(),
    created_at timestamptz NOT NULL DEFAULT now(),
    published_at timestamptz,
    error_code varchar(64)
);
CREATE INDEX ix_outbox_pending ON event_outbox(next_attempt_at, created_at) WHERE status='PENDING';
