package dev.stockping.demand.controller;

import dev.stockping.demand.ai.*;
import dev.stockping.demand.dto.AiDtos.*;
import dev.stockping.demand.grpc.MessageCatalogClient;
import dev.stockping.security.Identity;
import jakarta.validation.Valid;
import java.util.*;
import java.util.stream.Collectors;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/ai")
public class AiController {

    private static final String DISCLAIMER = "AI output is advisory. Inventory quantities, consent records, and waitlist allocations are executed through authoritative Catalog and Demand services.";

    private final AiExtractor aiExtractor;
    private final MessageCatalogClient catalog;
    private final AiProperties properties;

    public AiController(AiExtractor aiExtractor, MessageCatalogClient catalog, AiProperties properties) {
        this.aiExtractor = aiExtractor;
        this.catalog = catalog;
        this.properties = properties;
    }

    @PostMapping("/query-preview")
    public AiQueryResponse queryPreview(@Valid @RequestBody AiQueryRequest request) {
        Identity identity = Identity.current();
        UUID merchantId = identity.merchantId();
        String correlationId = UUID.randomUUID().toString();

        long start = System.currentTimeMillis();
        ProductExtractionResult extraction = aiExtractor.extract(request.query(), correlationId);
        long latency = System.currentTimeMillis() - start;

        ExtractedDetails details = new ExtractedDetails(
                extraction.productPhrase(),
                extraction.brand(),
                extraction.model(),
                extraction.attributes()
        );

        if (extraction.intent() == AiIntent.OPT_OUT) {
            return new AiQueryResponse(
                    request.query(), extraction.intent(), extraction.confidence(), false, "",
                    details, "N/A", null,
                    "You will not receive restock notifications from this merchant.",
                    "OPT_OUT", DISCLAIMER, extraction.providerModel(), latency
            );
        }

        if (extraction.intent() == AiIntent.HELP) {
            return new AiQueryResponse(
                    request.query(), extraction.intent(), extraction.confidence(), false, "",
                    details, "N/A", null,
                    "StockPing can check product stock and manage restock alerts. Ask in natural language or send STOCK <SKU>.",
                    "HELP", DISCLAIMER, extraction.providerModel(), latency
            );
        }

        if (extraction.needsClarification() || extraction.confidence() < properties.getMinConfidence()
                || extraction.intent() == AiIntent.UNKNOWN) {
            String prompt = (extraction.clarificationPrompt() != null && !extraction.clarificationPrompt().isBlank())
                    ? extraction.clarificationPrompt()
                    : "I could not identify the product you are asking about. Please provide the model name or SKU.";
            return new AiQueryResponse(
                    request.query(), extraction.intent(), extraction.confidence(), true, prompt,
                    details, "UNKNOWN", null, prompt,
                    "CLARIFICATION", DISCLAIMER, extraction.providerModel(), latency
            );
        }

        // Resolve against Catalog
        String nameToSearch = (extraction.model() != null && !extraction.model().isBlank())
                ? extraction.model() : extraction.productPhrase();

        var search = catalog.search(merchantId, nameToSearch, extraction.brand(), null, extraction.attributes(), correlationId);
        if (search.candidates().isEmpty() && extraction.productPhrase() != null && !extraction.productPhrase().isBlank()
                && !extraction.productPhrase().equalsIgnoreCase(nameToSearch)) {
            search = catalog.search(merchantId, extraction.productPhrase(), extraction.brand(), null, extraction.attributes(), correlationId);
        }
        if (search.candidates().isEmpty() && !extraction.attributes().isEmpty()) {
            search = catalog.search(merchantId, nameToSearch, extraction.brand(), null, Map.of(), correlationId);
        }

        if (search.candidates().isEmpty()) {
            return new AiQueryResponse(
                    request.query(), extraction.intent(), extraction.confidence(), false, "",
                    details, "NOT_FOUND", null,
                    "We couldn't find a matching product in our catalog for '" + nameToSearch + "'. Check the SKU or model name and try again.",
                    "STOCK_NOT_FOUND", DISCLAIMER, extraction.providerModel(), latency
            );
        }

        if (search.candidates().size() > 1) {
            String options = search.candidates().stream().limit(3)
                    .map(c -> c.productName() + " (" + c.sku() + ")")
                    .collect(Collectors.joining("; "));
            return new AiQueryResponse(
                    request.query(), extraction.intent(), extraction.confidence(), true,
                    "We found multiple options: " + options + ". Please specify the exact storage or color.",
                    details, "AMBIGUOUS", null,
                    "We found multiple options: " + options + ". Please specify the exact storage or color.",
                    "STOCK_AMBIGUOUS", DISCLAIMER, extraction.providerModel(), latency
            );
        }

        var candidate = search.candidates().get(0);
        var inventory = catalog.getInventory(merchantId, candidate.variantId(), correlationId);

        MatchedVariantView variantView = new MatchedVariantView(
                candidate.variantId(),
                candidate.sku(),
                candidate.productName(),
                candidate.brand(),
                candidate.attributes(),
                inventory.available(),
                inventory.quantity()
        );

        String reply;
        String purpose;
        if (inventory.available()) {
            reply = candidate.productName() + " (" + candidate.sku() + ") is currently available.";
            purpose = "STOCK_AVAILABLE";
        } else {
            reply = candidate.productName() + " (" + candidate.sku() + ") is currently out of stock. Would you like to be notified when it arrives? Reply YES or send WAITLIST " + candidate.sku() + " to join the waitlist.";
            purpose = "STOCK_WAITLIST_OFFER";
        }

        return new AiQueryResponse(
                request.query(), extraction.intent(), extraction.confidence(), false, "",
                details, "MATCHED", variantView, reply, purpose, DISCLAIMER, extraction.providerModel(), latency
        );
    }
}
