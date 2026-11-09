package store

import (
	"context"
	"database/sql"
	"errors"
	"github.com/google/uuid"
	"stockping.dev/messaging-service/internal/model"
)

// Receive is repeatable; the ID and immutable context must agree on every replay.
func (p *Postgres) ReceiveNotification(ctx context.Context, n model.Notification) (model.NotificationResult, error) {
	_, err := p.DB.ExecContext(ctx, `INSERT INTO notification_receipts(id,merchant_id,availability_event_id,correlation_id) VALUES($1,$2,$3,$4) ON CONFLICT DO NOTHING`, n.NotificationID, n.MerchantID, n.AvailabilityEventID, n.CorrelationID)
	if err != nil {
		return model.NotificationResult{}, err
	}
	var result model.NotificationResult
	err = p.DB.QueryRowContext(ctx, `SELECT status,coalesce(reason,''),coalesce(provider_message_id,'') FROM notification_receipts WHERE id=$1 AND merchant_id=$2 AND availability_event_id=$3 AND correlation_id=$4`, n.NotificationID, n.MerchantID, n.AvailabilityEventID, n.CorrelationID).Scan(&result.Status, &result.Reason, &result.ProviderID)
	return result, err
}

// Only the transaction that changes RECEIVED to SENDING may call the external provider.
func (p *Postgres) BeginNotification(ctx context.Context, n model.Notification, phone string) (string, error) {
	tx, err := p.DB.BeginTx(ctx, nil)
	if err != nil {
		return "", err
	}
	defer tx.Rollback()
	var claimed string
	err = tx.QueryRowContext(ctx, `UPDATE notification_receipts SET status='SENDING',attempts=1,attempted_at=now() WHERE id=$1 AND merchant_id=$2 AND status='RECEIVED' RETURNING id`, n.NotificationID, n.MerchantID).Scan(&claimed)
	if errors.Is(err, sql.ErrNoRows) {
		return "", nil
	}
	if err != nil {
		return "", err
	}
	id := uuid.NewString()
	_, err = tx.ExecContext(ctx, `INSERT INTO message_records(id,merchant_id,notification_id,customer_phone,direction,purpose,status,correlation_id) VALUES($1,$2,$3,$4,'OUTBOUND','RESTOCK_ALERT','SENDING',$5)`, id, n.MerchantID, n.NotificationID, phone, n.CorrelationID)
	if err != nil {
		return "", err
	}
	return id, tx.Commit()
}
func (p *Postgres) FinishNotification(ctx context.Context, n model.Notification, result model.NotificationResult) error {
	if result.Status == "SKIPPED" {
		update, err := p.DB.ExecContext(ctx, `UPDATE notification_receipts SET status='SKIPPED',reason=NULLIF($2,''),completed_at=now() WHERE id=$1 AND status='RECEIVED'`, n.NotificationID, result.Reason)
		if err != nil {
			return err
		}
		affected, err := update.RowsAffected()
		if err != nil {
			return err
		}
		if affected != 1 {
			return errors.New("notification_claim_changed")
		}
		return nil
	}
	// EndOutbound already reconciles provider callbacks; repeat safely after a crash between these writes.
	var messageID string
	err := p.DB.QueryRowContext(ctx, `SELECT id FROM message_records WHERE notification_id=$1`, n.NotificationID).Scan(&messageID)
	if err != nil && !errors.Is(err, sql.ErrNoRows) {
		return err
	}
	if messageID != "" {
		if err = p.EndOutbound(ctx, messageID, n.MerchantID, result.ProviderID, result.Status, result.Reason); err != nil {
			return err
		}
	}
	_, err = p.DB.ExecContext(ctx, `UPDATE notification_receipts SET status=$2,reason=NULLIF($3,''),provider_message_id=NULLIF($4,''),completed_at=now() WHERE id=$1 AND status IN ('RECEIVED','SENDING')`, n.NotificationID, result.Status, result.Reason, result.ProviderID)
	return err
}
func (p *Postgres) NotificationReported(ctx context.Context, id string) error {
	_, err := p.DB.ExecContext(ctx, `UPDATE notification_receipts SET reported_at=now() WHERE id=$1`, id)
	return err
}

// A crashed claim is never resent. A persisted provider result is recovered; otherwise the outcome is UNKNOWN.
func (p *Postgres) RecoverNotification(ctx context.Context, n model.Notification) error {
	tx, err := p.DB.BeginTx(ctx, nil)
	if err != nil {
		return err
	}
	defer tx.Rollback()
	var id string
	err = tx.QueryRowContext(ctx, `SELECT id FROM notification_receipts WHERE id=$1 AND status='SENDING' AND attempted_at < now()-interval '30 seconds' FOR UPDATE`, n.NotificationID).Scan(&id)
	if errors.Is(err, sql.ErrNoRows) {
		return nil
	}
	if err != nil {
		return err
	}
	var status, provider, reason string
	err = tx.QueryRowContext(ctx, `SELECT status,coalesce(provider_message_id,''),coalesce(error_code,'') FROM message_records WHERE notification_id=$1`, id).Scan(&status, &provider, &reason)
	if err != nil {
		return err
	}
	if provider != "" {
		status = "ACCEPTED"
	} else if status == "SENDING" {
		status = "UNKNOWN"
		reason = "interrupted_send"
	}
	_, err = tx.ExecContext(ctx, `UPDATE notification_receipts SET status=$2,reason=NULLIF($3,''),provider_message_id=NULLIF($4,''),completed_at=now() WHERE id=$1`, id, status, reason, provider)
	if err != nil {
		return err
	}
	_, err = tx.ExecContext(ctx, `UPDATE message_records SET status=$2,error_code=NULLIF($3,''),updated_at=now() WHERE notification_id=$1 AND status='SENDING'`, id, status, reason)
	if err != nil {
		return err
	}
	return tx.Commit()
}
