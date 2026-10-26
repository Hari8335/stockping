package dev.stockping.demand.controller;

import dev.stockping.demand.domain.WaitlistStatus;
import dev.stockping.demand.dto.DemandDtos.*;
import dev.stockping.demand.service.WaitlistService;
import jakarta.validation.Valid;
import java.net.URI;
import java.time.Instant;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
public class WaitlistController {
    private final WaitlistService waitlists;
    public WaitlistController(WaitlistService waitlists) { this.waitlists = waitlists; }
    @PostMapping("/api/waitlists")
    public ResponseEntity<WaitlistView> create(@Valid @RequestBody CreateWaitlistRequest request) {
        var result = waitlists.create(request);
        return ResponseEntity.created(URI.create("/api/waitlists/" + result.id())).body(result);
    }
    @GetMapping("/api/waitlists")
    public PageView<WaitlistView> list(@RequestParam(required = false) WaitlistStatus status,
            @RequestParam(required = false) UUID variantId, @RequestParam(required = false) UUID customerId,
            @RequestParam(required = false) Instant from, @RequestParam(required = false) Instant to,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "25") int size) {
        return waitlists.list(status, variantId, customerId, from, to, page, size);
    }
    @GetMapping("/api/waitlists/{id}")
    public WaitlistView detail(@PathVariable UUID id) { return waitlists.detail(id); }
    @GetMapping("/api/analytics/demand")
    public DemandSummary summary() { return waitlists.summary(); }
}
