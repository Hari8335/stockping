package contracts_test

import (
	"encoding/hex"
	"testing"

	"google.golang.org/protobuf/proto"
	skeletonv1 "stockping.dev/messaging-service/gen/stockping/skeleton/v1"
)

func TestSharedWireFixtureAndGeneratedRPCDescriptor(t *testing.T) {
	request := &skeletonv1.ProbeRequest{Message: "stockping"}
	wire, err := proto.Marshal(request)
	if err != nil {
		t.Fatal(err)
	}
	if hex.EncodeToString(wire) != "0a0973746f636b70696e67" {
		t.Fatalf("unexpected wire bytes: %x", wire)
	}
	fixture, _ := hex.DecodeString("0a0973746f636b70696e67")
	var decoded skeletonv1.ProbeRequest
	if err := proto.Unmarshal(fixture, &decoded); err != nil {
		t.Fatal(err)
	}
	if !proto.Equal(request, &decoded) {
		t.Fatal("wire fixture failed round trip")
	}
	if skeletonv1.SkeletonProbe_Check_FullMethodName != "/stockping.skeleton.v1.SkeletonProbe/Check" {
		t.Fatal("unexpected RPC descriptor")
	}
}
