package dev.stockping.contracts;

import dev.stockping.contracts.skeleton.v1.ProbeRequest;
import dev.stockping.contracts.skeleton.v1.SkeletonProbeGrpc;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;

class ProbeContractTest {
    @Test
    void matchesTheSharedWireFixtureAndGeneratedRpcDescriptor() throws Exception {
        var request = ProbeRequest.newBuilder().setMessage("stockping").build();
        var bytes = HexFormat.of().parseHex("0a0973746f636b70696e67");
        assertEquals(HexFormat.of().formatHex(bytes), HexFormat.of().formatHex(request.toByteArray()));
        assertEquals(request, ProbeRequest.parseFrom(bytes));
        assertEquals("stockping.skeleton.v1.SkeletonProbe/Check",
                SkeletonProbeGrpc.getCheckMethod().getFullMethodName());
    }
}

