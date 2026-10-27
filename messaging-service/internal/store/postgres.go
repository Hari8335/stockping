package store

import (
	"context"
	"crypto/sha256"
	"database/sql"
	"embed"
	"encoding/hex"
	"errors"
	"fmt"
	"github.com/google/uuid"
	_ "github.com/lib/pq"
	"stockping.dev/messaging-service/internal/model"
	"time"
)

//go:embed migrations/*.sql
var migrations embed.FS

type Postgres struct{ DB *sql.DB }

func Open(ctx context.Context, dsn string) (*Postgres, error) {
	db, err := sql.Open("postgres", dsn)
	if err != nil {
		return nil, errors.New("messaging_database_configuration")
	}
	db.SetMaxOpenConns(10)
	db.SetMaxIdleConns(5)
	db.SetConnMaxLifetime(30 * time.Minute)
	if err = db.PingContext(ctx); err != nil {
		_ = db.Close()
		return nil, errors.New("messaging_database_unavailable")
	}
	p := &Postgres{db}
	if err = p.Migrate(ctx); err != nil {
		_ = db.Close()
		return nil, errors.New("messaging_migration_failed")
	}
	return p, nil
}

// Transactional, checksum-verified numbered migrations. Advisory lock serializes startups.
func (p *Postgres) Migrate(ctx context.Context) error {
	tx, err := p.DB.BeginTx(ctx, nil)
	if err != nil {
		return err
	}
	defer tx.Rollback()
	if _, err = tx.ExecContext(ctx, `SELECT pg_advisory_xact_lock(735031); CREATE TABLE IF NOT EXISTS schema_migrations (version integer PRIMARY KEY, checksum text NOT NULL, applied_at timestamptz NOT NULL DEFAULT now())`); err != nil {
		return err
	}
	body, err := migrations.ReadFile("migrations/001_messaging.sql")
	if err != nil {
		return err
	}
	sum := sha256.Sum256(body)
	checksum := hex.EncodeToString(sum[:])
	var existing string
	err = tx.QueryRowContext(ctx, "SELECT checksum FROM schema_migrations WHERE version=1").Scan(&existing)
	if errors.Is(err, sql.ErrNoRows) {
		if _, err = tx.ExecContext(ctx, string(body)); err != nil {
			return err
		}
		if _, err = tx.ExecContext(ctx, "INSERT INTO schema_migrations(version,checksum) VALUES(1,$1)", checksum); err != nil {
			return err
		}
	} else if err != nil {
		return err
	} else if existing != checksum {
		return errors.New("migration_checksum_mismatch")
	}
	return tx.Commit()
}
func (p *Postgres) Claim(ctx context.Context, m model.Message) (string, error) {
	tx, err := p.DB.BeginTx(ctx, nil)
	if err != nil {
		return "", err
	}
	defer tx.Rollback()
	id := uuid.NewString()
	var inserted string
	err = tx.QueryRowContext(ctx, `INSERT INTO webhook_events(id,provider_event_id,merchant_id,event_type,status) VALUES($1,$2,$3,'INBOUND','PROCESSING') ON CONFLICT(merchant_id,provider_event_id) DO NOTHING RETURNING id`, id, "inbound:"+m.ProviderID, m.MerchantID).Scan(&inserted)
	if errors.Is(err, sql.ErrNoRows) {
		return "", nil
	}
	if err != nil {
		return "", err
	}
	_, err = tx.ExecContext(ctx, `INSERT INTO message_records(id,merchant_id,webhook_event_id,customer_phone,direction,purpose,provider_message_id,status,correlation_id) VALUES($1,$2,$3,$4,'INBOUND','CUSTOMER_MESSAGE',$5,'PROCESSING',$6)`, uuid.NewString(), m.MerchantID, id, m.Phone, m.ProviderID, m.CorrelationID)
	if err != nil {
		return "", err
	}
	return id, tx.Commit()
}
func (p *Postgres) Finish(ctx context.Context, id, state, code string) error {
	tx, err := p.DB.BeginTx(ctx, nil)
	if err != nil {
		return err
	}
	defer tx.Rollback()
	if _, err = tx.ExecContext(ctx, "UPDATE webhook_events SET status=$2,error_code=NULLIF($3,''),processed_at=now() WHERE id=$1", id, state, code); err != nil {
		return err
	}
	if _, err = tx.ExecContext(ctx, "UPDATE message_records SET status=$2,error_code=NULLIF($3,''),updated_at=now() WHERE webhook_event_id=$1 AND direction='INBOUND'", id, state, code); err != nil {
		return err
	}
	return tx.Commit()
}
func (p *Postgres) BeginOutbound(ctx context.Context, eventID string, m model.Message, purpose string) (string, error) {
	id := uuid.NewString()
	_, err := p.DB.ExecContext(ctx, `INSERT INTO message_records(id,merchant_id,webhook_event_id,customer_phone,direction,purpose,status,correlation_id) VALUES($1,$2,$3,$4,'OUTBOUND',$5,'SENDING',$6)`, id, m.MerchantID, eventID, m.Phone, purpose, m.CorrelationID)
	return id, err
}
func (p *Postgres) EndOutbound(ctx context.Context, id, merchant, providerID, state, code string) error {
	tx, err := p.DB.BeginTx(ctx, nil)
	if err != nil {
		return err
	}
	defer tx.Rollback()
	// Serialize reconciliation with callbacks, including callbacks arriving before the send result is committed.
	if providerID != "" {
		if _, err = tx.ExecContext(ctx, "SELECT pg_advisory_xact_lock(hashtextextended($1,0))", merchant+":"+providerID); err != nil {
			return err
		}
	}
	if _, err = tx.ExecContext(ctx, "UPDATE message_records SET provider_message_id=NULLIF($2,''),status=$3,error_code=NULLIF($4,''),updated_at=now() WHERE id=$1", id, providerID, state, code); err != nil {
		return err
	}
	if providerID != "" {
		if _, err = tx.ExecContext(ctx, "UPDATE delivery_events SET message_record_id=$1 WHERE merchant_id=$2 AND provider_message_id=$3", id, merchant, providerID); err != nil {
			return err
		}
		if err = refreshStatus(ctx, tx, merchant, providerID); err != nil {
			return err
		}
	}
	return tx.Commit()
}
func (p *Postgres) Delivery(ctx context.Context, d model.Delivery) error {
	tx, err := p.DB.BeginTx(ctx, nil)
	if err != nil {
		return err
	}
	defer tx.Rollback()
	if _, err = tx.ExecContext(ctx, "SELECT pg_advisory_xact_lock(hashtextextended($1,0))", d.MerchantID+":"+d.ProviderID); err != nil {
		return err
	}
	id := uuid.NewString()
	var inserted string
	key := fmt.Sprintf("status:%s:%s:%d", d.ProviderID, d.Status, d.Time.Unix())
	err = tx.QueryRowContext(ctx, `INSERT INTO webhook_events(id,provider_event_id,merchant_id,event_type,status,processed_at) VALUES($1,$2,$3,'DELIVERY','PROCESSED',now()) ON CONFLICT(merchant_id,provider_event_id) DO NOTHING RETURNING id`, id, key, d.MerchantID).Scan(&inserted)
	if errors.Is(err, sql.ErrNoRows) {
		return nil
	}
	if err != nil {
		return err
	}
	_, err = tx.ExecContext(ctx, `INSERT INTO delivery_events(id,merchant_id,webhook_event_id,message_record_id,provider_message_id,provider_status,event_time,raw_reference) VALUES($1,$2,$3,(SELECT id FROM message_records WHERE merchant_id=$2 AND provider_message_id=$4 AND direction='OUTBOUND'),$4,$5,$6,NULLIF($7,''))`, uuid.NewString(), d.MerchantID, id, d.ProviderID, d.Status, d.Time, d.ErrorCode)
	if err != nil {
		return err
	}
	if err = refreshStatus(ctx, tx, d.MerchantID, d.ProviderID); err != nil {
		return err
	}
	return tx.Commit()
}
func refreshStatus(ctx context.Context, tx *sql.Tx, merchant, provider string) error {
	_, err := tx.ExecContext(ctx, `UPDATE message_records SET status=upper(d.provider_status),updated_at=now() FROM (SELECT provider_status FROM delivery_events WHERE merchant_id=$1 AND provider_message_id=$2 ORDER BY event_time DESC, CASE provider_status WHEN 'read' THEN 4 WHEN 'delivered' THEN 3 WHEN 'failed' THEN 2 ELSE 1 END DESC LIMIT 1) d WHERE message_records.merchant_id=$1 AND message_records.provider_message_id=$2 AND direction='OUTBOUND'`, merchant, provider)
	return err
}
