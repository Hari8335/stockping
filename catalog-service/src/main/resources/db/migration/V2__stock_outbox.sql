CREATE TABLE event_outbox (
    id uuid PRIMARY KEY REFERENCES stock_events(id),
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
