// Live generated Go client -> production Java Demand server over TCP.
package main

import (
	"context"
	"fmt"
	"github.com/google/uuid"
	"google.golang.org/grpc"
	"google.golang.org/grpc/codes"
	"google.golang.org/grpc/credentials/insecure"
	"google.golang.org/grpc/metadata"
	"google.golang.org/grpc/status"
	"os"
	demandv1 "stockping.dev/messaging-service/gen/stockping/demand/v1"
	"time"
)

func main() {
	if err := run(); err != nil {
		fmt.Fprintln(os.Stderr, err)
		os.Exit(1)
	}
}
func run() error {
	conn, err := grpc.NewClient("demand-service:9092", grpc.WithTransportCredentials(insecure.NewCredentials()), grpc.WithDisableRetry())
	if err != nil {
		return fmt.Errorf("client construction failed")
	}
	defer conn.Close()
	client := demandv1.NewDemandServiceClient(conn)
	request := &demandv1.ProcessCustomerMessageRequest{MerchantId: os.Getenv("CHECK_MERCHANT_ID"), CustomerPhone: "+94770000000", ProviderMessageId: "synthetic.grpc.check", MessageText: "HELP", ReceivedAt: time.Now().UTC().Format(time.RFC3339), CorrelationId: uuid.NewString()}
	call := func(token string, timeout time.Duration) (*demandv1.ProcessCustomerMessageResponse, error) {
		ctx, cancel := context.WithTimeout(context.Background(), timeout)
		defer cancel()
		if token != "" {
			ctx = metadata.AppendToOutgoingContext(ctx, "x-service-token", token, "x-request-id", request.CorrelationId)
		}
		return client.ProcessCustomerMessage(ctx, request)
	}
	token := os.Getenv("MESSAGING_DEMAND_TOKEN")
	if os.Getenv("CHECK_DEADLINE") == "true" {
		// The validator holds a brief read-blocking lock on merchants. HELP has no business side effects.
		_, err = call(token, 100*time.Millisecond)
		if status.Code(err) != codes.DeadlineExceeded {
			return fmt.Errorf("expected deadline, got %s", status.Code(err))
		}
		fmt.Println("PASS Go -> Java: real TCP deadline while Demand waits for a controlled database lock")
		return nil
	}
	for _, credential := range []string{"", "invalid"} {
		_, err = call(credential, 3*time.Second)
		if status.Code(err) != codes.Unauthenticated {
			return fmt.Errorf("authentication mapping failed")
		}
	}
	response, err := call(token, 3*time.Second)
	if err != nil {
		return fmt.Errorf("HELP RPC failed: %s", status.Code(err))
	}
	if response.Action != demandv1.ReplyAction_SEND_TEXT || response.Purpose != "HELP" || response.ReplyText == "" || response.CorrelationId != request.CorrelationId {
		return fmt.Errorf("response serialization or correlation failed")
	}
	request.CustomerPhone = "invalid"
	_, err = call(token, time.Second)
	if status.Code(err) != codes.InvalidArgument {
		return fmt.Errorf("invalid argument mapping failed")
	}
	fmt.Println("PASS generated Go -> Java TCP: credentials, request/response, correlation, INVALID_ARGUMENT")
	return nil
}
