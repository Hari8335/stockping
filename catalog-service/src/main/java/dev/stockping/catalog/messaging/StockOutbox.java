package dev.stockping.catalog.messaging;

import dev.stockping.catalog.domain.*;
import dev.stockping.contracts.events.StockAvailable;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.*;
import tools.jackson.databind.ObjectMapper;

@Component
public class StockOutbox {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    public StockOutbox(JdbcTemplate jdbc, ObjectMapper json) { this.jdbc = jdbc; this.json = json; }

    @Transactional(propagation = Propagation.MANDATORY)
    public void record(StockEvent event, ProductVariant variant) {
        String correlation = MDC.get("request.id");
        try { StockAvailable.uuid(correlation); }
        catch (IllegalArgumentException e) { correlation = UUID.randomUUID().toString(); }
        var message = new StockAvailable(event.getId().toString(), StockAvailable.TYPE, 1, event.getCreatedAt().toString(),
                event.getMerchantId().toString(), variant.getProduct().getId().toString(), variant.getId().toString(),
                variant.getSku(), event.getPreviousQuantity(), event.getNewQuantity(), correlation);
        message.validate();
        jdbc.update("INSERT INTO event_outbox(id,routing_key,payload) VALUES(?,?,?::jsonb)",
                event.getId(), StockAvailable.TYPE, json.writeValueAsString(message));
    }
}
