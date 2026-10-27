ALTER TABLE consent_records DROP CONSTRAINT consent_records_source_check;
ALTER TABLE consent_records ADD CONSTRAINT consent_records_source_check
    CHECK (source IN ('MANUAL_API', 'CUSTOMER_MESSAGE'));
