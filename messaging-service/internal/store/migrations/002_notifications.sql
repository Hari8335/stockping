CREATE TABLE notification_receipts (
    id uuid PRIMARY KEY,
    merchant_id uuid NOT NULL,
    availability_event_id uuid NOT NULL,
    correlation_id uuid NOT NULL,
    status varchar(16) NOT NULL DEFAULT 'RECEIVED' CHECK (status IN ('RECEIVED','SENDING','ACCEPTED','FAILED','UNKNOWN','SKIPPED')),
    reason varchar(64),
    provider_message_id varchar(512),
    attempts integer NOT NULL DEFAULT 0 CHECK (attempts BETWEEN 0 AND 1),
    created_at timestamptz NOT NULL DEFAULT now(),
    attempted_at timestamptz,
    completed_at timestamptz,
    reported_at timestamptz
);
ALTER TABLE message_records ALTER COLUMN webhook_event_id DROP NOT NULL;
ALTER TABLE message_records ADD COLUMN notification_id uuid UNIQUE REFERENCES notification_receipts(id);
ALTER TABLE message_records ADD CONSTRAINT message_origin CHECK (
    (webhook_event_id IS NOT NULL AND notification_id IS NULL) OR
    (webhook_event_id IS NULL AND notification_id IS NOT NULL AND direction='OUTBOUND' AND purpose='RESTOCK_ALERT')
);
CREATE INDEX ix_notification_recovery ON notification_receipts(status, attempted_at);
