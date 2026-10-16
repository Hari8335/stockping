// Validation-only generated-code client. It is not linked into any service.
package main

import (
	"context"
	"flag"
	"fmt"
	"os"
	"time"

	"google.golang.org/grpc"
	"google.golang.org/grpc/credentials/insecure"
	catalogv1 "stockping.dev/messaging-service/gen/stockping/catalog/v1"
)

func main() {
	target := flag.String("target", "host.docker.internal:9091", "private development gRPC address")
	merchant := flag.String("merchant", "11111111-1111-1111-1111-111111111111", "server-configured merchant assertion")
	sku := flag.String("sku", "", "exact known SKU")
	quantity := flag.Int("quantity", 5, "expected authoritative quantity")
	flag.Parse()
	if err := check(*target, *merchant, *sku, *quantity); err != nil {
		fmt.Fprintln(os.Stderr, err)
		os.Exit(1)
	}
}

func check(target, merchant, sku string, quantity int) error {
	conn, err := grpc.NewClient(target, grpc.WithTransportCredentials(insecure.NewCredentials()))
	if err != nil {
		return err
	}
	defer conn.Close()
	client := catalogv1.NewCatalogServiceClient(conn)
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()
	resolved, err := client.ResolveProductVariant(ctx, &catalogv1.ResolveProductVariantRequest{MerchantId: merchant, Sku: sku})
	if err != nil {
		return err
	}
	if resolved.Status != catalogv1.ResolutionStatus_MATCHED || len(resolved.Candidates) != 1 {
		return fmt.Errorf("expected one exact match, got %s (%d matches)", resolved.Status, resolved.TotalMatches)
	}
	variant := resolved.Candidates[0]
	inventoryCtx, inventoryCancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer inventoryCancel()
	inventory, err := client.GetInventory(inventoryCtx, &catalogv1.GetInventoryRequest{MerchantId: merchant, VariantId: variant.VariantId})
	if err != nil {
		return err
	}
	expected := catalogv1.AvailabilityStatus_UNAVAILABLE
	if quantity > 0 {
		expected = catalogv1.AvailabilityStatus_AVAILABLE
	}
	if int(inventory.Quantity) != quantity || inventory.AvailabilityStatus != expected || inventory.UpdatedAt == "" {
		return fmt.Errorf("unexpected inventory response: %v", inventory)
	}
	fmt.Printf("PASS Catalog gRPC: SKU=%s variant=%s quantity=%d availability=%s\n", sku, variant.VariantId, inventory.Quantity, inventory.AvailabilityStatus)
	return nil
}
