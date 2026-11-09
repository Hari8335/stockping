package rabbitmq

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	amqp "github.com/rabbitmq/amqp091-go"
	"io"
	"log/slog"
	"net"
	"stockping.dev/messaging-service/internal/model"
	"time"
)

const Queue = "messaging.notification-requested.v1"

type Processor interface {
	Process(context.Context, model.Notification) error
}
type Consumer struct {
	URL       string
	Processor Processor
	Logger    *slog.Logger
}

func (c *Consumer) Run(ctx context.Context) {
	for ctx.Err() == nil {
		if err := c.session(ctx); err != nil && ctx.Err() == nil {
			c.Logger.Warn("notification consumer reconnecting", "errorCode", "broker_connection")
		}
		select {
		case <-ctx.Done():
			return
		case <-time.After(3 * time.Second):
		}
	}
}
func (c *Consumer) session(ctx context.Context) error {
	connection, err := amqp.DialConfig(c.URL, amqp.Config{Heartbeat: 10 * time.Second, Locale: "en_US", Dial: func(network, address string) (net.Conn, error) {
		return (&net.Dialer{Timeout: 3 * time.Second}).DialContext(ctx, network, address)
	}})
	if err != nil {
		return errors.New("broker_connection")
	}
	defer connection.Close()
	channel, err := connection.Channel()
	if err != nil {
		return err
	}
	defer channel.Close()
	for _, exchange := range []string{"stockping.events", "stockping.dead"} {
		if err = channel.ExchangeDeclare(exchange, "direct", true, false, false, false, nil); err != nil {
			return err
		}
	}
	if _, err = channel.QueueDeclare(Queue+".dead", true, false, false, false, amqp.Table{"x-queue-type": "quorum"}); err != nil {
		return err
	}
	if err = channel.QueueBind(Queue+".dead", Queue, "stockping.dead", false, nil); err != nil {
		return err
	}
	if _, err = channel.QueueDeclare(Queue, true, false, false, false, amqp.Table{"x-queue-type": "quorum", "x-delivery-limit": int32(5), "x-dead-letter-exchange": "stockping.dead", "x-dead-letter-routing-key": Queue, "x-dead-letter-strategy": "at-least-once", "x-overflow": "reject-publish"}); err != nil {
		return err
	}
	if err = channel.QueueBind(Queue, "notification.requested.v1", "stockping.events", false, nil); err != nil {
		return err
	}
	if err = channel.Qos(1, 0, false); err != nil {
		return err
	}
	messages, err := channel.Consume(Queue, "", false, false, false, false, nil)
	if err != nil {
		return err
	}
	for {
		select {
		case <-ctx.Done():
			return nil
		case message, ok := <-messages:
			if !ok {
				return errors.New("broker_closed")
			}
			event, err := decode(message.Body)
			if err != nil {
				c.Logger.Warn("notification rejected", "errorCode", "invalid_event")
				if err = message.Reject(false); err != nil {
					return err
				}
				continue
			}
			processing, cancel := context.WithTimeout(ctx, 45*time.Second)
			err = c.Processor.Process(processing, event)
			cancel()
			if err != nil {
				c.Logger.Warn("notification processing deferred", "notificationId", event.NotificationID, "errorCode", "processing_failed")
				select {
				case <-ctx.Done():
					return nil
				case <-time.After(time.Second):
				}
				if err = message.Reject(true); err != nil {
					return err
				}
			} else if err = message.Ack(false); err != nil {
				return err
			}
		}
	}
}
func decode(body []byte) (model.Notification, error) {
	var n model.Notification
	if len(body) > 16384 {
		return n, errors.New("invalid_event")
	}
	decoder := json.NewDecoder(bytes.NewReader(body))
	decoder.DisallowUnknownFields()
	if err := decoder.Decode(&n); err != nil {
		return n, err
	}
	if err := decoder.Decode(new(any)); err != io.EOF {
		return n, errors.New("invalid_event")
	}
	return n, n.Validate()
}
