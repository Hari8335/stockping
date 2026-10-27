package model

import "time"

type Message struct {
	MerchantID, Phone, DisplayName, ProviderID, Text, CorrelationID string
	ReceivedAt                                                      time.Time
	Supported                                                       bool
}
type Reply struct {
	Text, Purpose, CorrelationID string
	Send                         bool
}
type Delivery struct {
	MerchantID, ProviderID, Status, ErrorCode string
	Time                                      time.Time
}
