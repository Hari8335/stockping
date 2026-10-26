CREATE TABLE merchants (
    id uuid PRIMARY KEY,
    name varchar(200) NOT NULL,
    timezone varchar(80) NOT NULL,
    status varchar(16) NOT NULL CHECK (status IN ('ACTIVE', 'INACTIVE')),
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL
);

CREATE TABLE users (
    id uuid PRIMARY KEY,
    merchant_id uuid NOT NULL REFERENCES merchants(id),
    email varchar(254) NOT NULL UNIQUE CHECK (email = lower(trim(email))),
    password_hash varchar(100) NOT NULL,
    role varchar(32) NOT NULL CHECK (role = 'MERCHANT_ADMIN'),
    status varchar(16) NOT NULL CHECK (status IN ('ACTIVE', 'INACTIVE')),
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL
);
CREATE INDEX ix_users_merchant ON users(merchant_id);

CREATE TABLE customers (
    id uuid PRIMARY KEY,
    merchant_id uuid NOT NULL REFERENCES merchants(id),
    whatsapp_phone varchar(16) NOT NULL CHECK (whatsapp_phone ~ '^\+[1-9][0-9]{7,14}$'),
    display_name varchar(120),
    language varchar(16),
    status varchar(16) NOT NULL CHECK (status IN ('ACTIVE', 'INACTIVE')),
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    CONSTRAINT uq_customer_phone UNIQUE (merchant_id, whatsapp_phone),
    UNIQUE (merchant_id, id)
);

CREATE TABLE consent_records (
    id uuid PRIMARY KEY,
    sequence bigint GENERATED ALWAYS AS IDENTITY UNIQUE,
    merchant_id uuid NOT NULL REFERENCES merchants(id),
    customer_id uuid NOT NULL,
    type varchar(32) NOT NULL CHECK (type = 'RESTOCK_NOTIFICATIONS'),
    status varchar(16) NOT NULL CHECK (status IN ('OPT_IN', 'OPT_OUT')),
    source varchar(32) NOT NULL CHECK (source = 'MANUAL_API'),
    captured_at timestamptz NOT NULL,
    FOREIGN KEY (merchant_id, customer_id) REFERENCES customers(merchant_id, id)
);
CREATE INDEX ix_consent_history ON consent_records(merchant_id, customer_id, sequence DESC);

CREATE TABLE waitlist_requests (
    id uuid PRIMARY KEY,
    merchant_id uuid NOT NULL REFERENCES merchants(id),
    customer_id uuid NOT NULL,
    variant_id uuid NOT NULL, -- Catalog-owned logical identity, deliberately no foreign key.
    status varchar(32) NOT NULL CHECK (status IN
        ('WAITING', 'NOTIFICATION_QUEUED', 'NOTIFIED', 'FULFILLED', 'EXPIRED', 'CANCELLED', 'OPTED_OUT')),
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    notified_at timestamptz,
    FOREIGN KEY (merchant_id, customer_id) REFERENCES customers(merchant_id, id)
);
CREATE UNIQUE INDEX uq_active_waitlist ON waitlist_requests(merchant_id, customer_id, variant_id)
    WHERE status IN ('WAITING', 'NOTIFICATION_QUEUED');
CREATE INDEX ix_waitlist_list ON waitlist_requests(merchant_id, created_at DESC, id);
CREATE INDEX ix_waitlist_variant ON waitlist_requests(merchant_id, variant_id, status);
CREATE INDEX ix_waitlist_customer ON waitlist_requests(merchant_id, customer_id, status);

CREATE TABLE demand_audit (
    id uuid PRIMARY KEY,
    merchant_id uuid NOT NULL REFERENCES merchants(id),
    event_type varchar(40) NOT NULL,
    entity_id uuid NOT NULL,
    metadata_json jsonb NOT NULL,
    created_at timestamptz NOT NULL
);
CREATE INDEX ix_demand_audit_merchant ON demand_audit(merchant_id, created_at DESC);
