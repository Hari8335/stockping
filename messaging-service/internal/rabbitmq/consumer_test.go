package rabbitmq

import (
	"encoding/json"
	"github.com/google/uuid"
	"stockping.dev/messaging-service/internal/model"
	"testing"
	"time"
)

func TestNotificationContractRejectsPoison(t *testing.T) {
	id := uuid.NewString()
	n := model.Notification{EventID: id, NotificationID: id, EventType: "notification.requested.v1", EventVersion: 1, OccurredAt: time.Now().Format(time.RFC3339), MerchantID: uuid.NewString(), AvailabilityEventID: uuid.NewString(), CorrelationID: uuid.NewString()}
	body, _ := json.Marshal(n)
	if _, err := decode(body); err != nil {
		t.Fatal(err)
	}
	for _, bad := range [][]byte{[]byte(`{}`), []byte(`{"eventVersion":2}`), append(body, []byte(` {}`)...), make([]byte, 16385)} {
		if _, err := decode(bad); err == nil {
			t.Fatal("poison accepted")
		}
	}
	n.EventVersion = 2
	body, _ = json.Marshal(n)
	if _, err := decode(body); err == nil {
		t.Fatal("future version accepted")
	}
}
