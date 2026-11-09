package demandgrpc

import (
	"context"
	"crypto/tls"
	"errors"
	"google.golang.org/grpc"
	"google.golang.org/grpc/codes"
	"google.golang.org/grpc/credentials"
	"google.golang.org/grpc/credentials/insecure"
	"google.golang.org/grpc/metadata"
	"google.golang.org/grpc/status"
	demandv1 "stockping.dev/messaging-service/gen/stockping/demand/v1"
	"stockping.dev/messaging-service/internal/model"
	"time"
)

type Client struct {
	conn    *grpc.ClientConn
	client  demandv1.DemandServiceClient
	token   string
	timeout time.Duration
}

func New(target, token string, timeout time.Duration, tlsEnabled bool) (*Client, error) {
	var transport credentials.TransportCredentials = insecure.NewCredentials()
	if tlsEnabled {
		transport = credentials.NewTLS(&tls.Config{MinVersion: tls.VersionTLS12})
	}
	conn, err := grpc.NewClient(target, grpc.WithTransportCredentials(transport), grpc.WithDisableRetry(), grpc.WithDefaultCallOptions(grpc.MaxCallRecvMsgSize(64*1024)))
	if err != nil {
		return nil, errors.New("demand_configuration")
	}
	return &Client{conn, demandv1.NewDemandServiceClient(conn), token, timeout}, nil
}
func (c *Client) Close() error { return c.conn.Close() }
func (c *Client) Authorize(ctx context.Context, n model.Notification) (model.Authorization, error) {
	ctx, cancel := context.WithTimeout(ctx, c.timeout)
	defer cancel()
	ctx = metadata.AppendToOutgoingContext(ctx, "x-service-token", c.token, "x-request-id", n.CorrelationID)
	result, err := c.client.AuthorizeNotification(ctx, &demandv1.AuthorizeNotificationRequest{MerchantId: n.MerchantID, NotificationId: n.NotificationID, AvailabilityEventId: n.AvailabilityEventID, CorrelationId: n.CorrelationID})
	if err != nil {
		return model.Authorization{}, errors.New("authorization_unavailable")
	}
	return model.Authorization{Eligible: result.Eligible, Reason: result.Reason, Phone: result.CustomerPhone, ProductName: result.ProductName, SKU: result.Sku, VariantDescription: result.VariantDescription}, nil
}
func (c *Client) Complete(ctx context.Context, n model.Notification, r model.NotificationResult) error {
	ctx, cancel := context.WithTimeout(ctx, c.timeout)
	defer cancel()
	ctx = metadata.AppendToOutgoingContext(ctx, "x-service-token", c.token, "x-request-id", n.CorrelationID)
	_, err := c.client.CompleteNotification(ctx, &demandv1.CompleteNotificationRequest{MerchantId: n.MerchantID, NotificationId: n.NotificationID, Status: r.Status, Reason: r.Reason, ProviderMessageId: r.ProviderID})
	if err != nil {
		return errors.New("completion_unavailable")
	}
	return nil
}
func (c *Client) Process(ctx context.Context, m model.Message) (model.Reply, error) {
	ctx, cancel := context.WithTimeout(ctx, c.timeout)
	defer cancel()
	ctx = metadata.AppendToOutgoingContext(ctx, "x-service-token", c.token, "x-request-id", m.CorrelationID)
	response, err := c.client.ProcessCustomerMessage(ctx, &demandv1.ProcessCustomerMessageRequest{MerchantId: m.MerchantID, CustomerPhone: m.Phone, CustomerDisplayName: m.DisplayName, ProviderMessageId: m.ProviderID, MessageText: m.Text, ReceivedAt: m.ReceivedAt.Format(time.RFC3339), CorrelationId: m.CorrelationID})
	if err != nil {
		code := "demand_rejected"
		switch status.Code(err) {
		case codes.Unavailable:
			code = "demand_unavailable"
		case codes.DeadlineExceeded:
			code = "demand_timeout"
		case codes.Unauthenticated, codes.PermissionDenied:
			code = "demand_authentication"
		case codes.Internal, codes.Unknown:
			code = "demand_error"
		}
		return model.Reply{}, errors.New(code)
	}
	if response.CorrelationId != m.CorrelationID || (response.Action != demandv1.ReplyAction_SEND_TEXT && response.Action != demandv1.ReplyAction_NO_REPLY) || (response.Action == demandv1.ReplyAction_SEND_TEXT && (response.ReplyText == "" || len([]rune(response.ReplyText)) > 4096)) || len(response.Purpose) > 64 || response.Purpose == "" {
		return model.Reply{}, errors.New("demand_invalid_response")
	}
	return model.Reply{Text: response.ReplyText, Purpose: response.Purpose, CorrelationID: response.CorrelationId, Send: response.Action == demandv1.ReplyAction_SEND_TEXT}, nil
}
