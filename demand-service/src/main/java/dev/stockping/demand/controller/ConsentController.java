package dev.stockping.demand.controller;

import dev.stockping.demand.domain.ConsentStatus;
import dev.stockping.demand.dto.DemandDtos.ConsentView;
import dev.stockping.demand.service.ConsentService;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/customers")
public class ConsentController {
    private final ConsentService consent;
    public ConsentController(ConsentService consent) { this.consent = consent; }
    @PostMapping("/{id}/opt-out")
    public ConsentView optOut(@PathVariable UUID id) { return consent.change(id, ConsentStatus.OPT_OUT); }
    @PostMapping("/{id}/opt-in")
    public ConsentView optIn(@PathVariable UUID id) { return consent.change(id, ConsentStatus.OPT_IN); }
}
