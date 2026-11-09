package dev.stockping.demand.messaging;

import com.rabbitmq.client.Channel;
import dev.stockping.contracts.events.StockAvailable;
import org.slf4j.*;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@Component
@ConditionalOnProperty(name = "stockping.events.enabled", havingValue = "true")
public class StockEventConsumer {
    private static final Logger LOG = LoggerFactory.getLogger(StockEventConsumer.class);
    private final ObjectMapper json;
    private final NotificationService notifications;
    public StockEventConsumer(ObjectMapper json, NotificationService notifications) { this.json = json; this.notifications = notifications; }
    @RabbitListener(queues = EventConfiguration.STOCK_QUEUE)
    public void consume(Message message, Channel channel) throws java.io.IOException {
        long tag = message.getMessageProperties().getDeliveryTag();
        StockAvailable event;
        try {
            if (message.getBody().length > 16384) throw new IllegalArgumentException();
            event = json.readValue(message.getBody(), StockAvailable.class);
            event.validate();
        } catch (RuntimeException e) {
            LOG.warn("Stock event rejected errorCode=invalid_event");
            channel.basicReject(tag, false); return;
        }
        try {
            notifications.process(event);
            channel.basicAck(tag, false);
        } catch (Exception e) {
            LOG.warn("Stock event deferred eventId={} errorCode=processing_failed", event.eventId());
            // basic.reject counts failed attempts on RabbitMQ 4.x quorum queues (basic.nack may not).
            try { Thread.sleep(1000); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            channel.basicReject(tag, true);
        }
    }
}
