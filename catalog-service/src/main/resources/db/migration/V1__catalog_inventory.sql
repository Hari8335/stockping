CREATE TABLE products (
    id uuid PRIMARY KEY,
    merchant_id uuid NOT NULL,
    name varchar(200) NOT NULL CHECK (btrim(name) <> ''),
    brand varchar(100) NOT NULL CHECK (btrim(brand) <> ''),
    category varchar(100) NOT NULL CHECK (btrim(category) <> ''),
    status varchar(16) NOT NULL CHECK (status IN ('ACTIVE', 'INACTIVE')),
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    UNIQUE (merchant_id, id)
);
CREATE INDEX idx_products_merchant_status ON products (merchant_id, status, id);
CREATE TABLE product_variants (
    id uuid PRIMARY KEY,
    merchant_id uuid NOT NULL,
    product_id uuid NOT NULL,
    sku varchar(100) NOT NULL CHECK (btrim(sku) <> ''),
    attributes_json jsonb NOT NULL CHECK (jsonb_typeof(attributes_json) = 'object'),
    price numeric(19,2) CHECK (price >= 0),
    status varchar(16) NOT NULL CHECK (status IN ('ACTIVE', 'INACTIVE')),
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    CONSTRAINT uq_variant_merchant_sku UNIQUE (merchant_id, sku),
    UNIQUE (merchant_id, id),
    FOREIGN KEY (merchant_id, product_id) REFERENCES products (merchant_id, id)
);
CREATE INDEX idx_variants_merchant_product ON product_variants (merchant_id, product_id, id);
CREATE INDEX idx_variants_merchant_status ON product_variants (merchant_id, status, id);
CREATE TABLE inventory (
    id uuid PRIMARY KEY,
    merchant_id uuid NOT NULL,
    variant_id uuid NOT NULL,
    quantity integer NOT NULL CHECK (quantity >= 0),
    availability_status varchar(16) NOT NULL,
    updated_at timestamptz NOT NULL,
    UNIQUE (merchant_id, variant_id),
    FOREIGN KEY (merchant_id, variant_id) REFERENCES product_variants (merchant_id, id),
    CHECK ((quantity = 0 AND availability_status = 'UNAVAILABLE') OR
           (quantity > 0 AND availability_status = 'AVAILABLE'))
);
CREATE TABLE stock_events (
    id uuid PRIMARY KEY,
    merchant_id uuid NOT NULL,
    variant_id uuid NOT NULL,
    previous_quantity integer NOT NULL CHECK (previous_quantity >= 0),
    new_quantity integer NOT NULL CHECK (new_quantity >= 0),
    source varchar(32) NOT NULL CHECK (source = 'MANUAL'),
    created_at timestamptz NOT NULL,
    FOREIGN KEY (merchant_id, variant_id) REFERENCES product_variants (merchant_id, id),
    CHECK (previous_quantity <> new_quantity)
);
CREATE INDEX idx_stock_events_merchant_variant_time ON stock_events (merchant_id, variant_id, created_at, id);
