package dev.stockping.demand.messaging;

import org.springframework.amqp.core.*;
import org.springframework.context.annotation.*;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "stockping.events.enabled", havingValue = "true")
public class EventConfiguration {
    public static final String EXCHANGE = "stockping.events";
    public static final String DEAD = "stockping.dead";
    public static final String STOCK_QUEUE = "demand.stock-available.v1";
    public static final String NOTIFICATION_QUEUE = "messaging.notification-requested.v1";
    @Bean Declarables eventTopology() {
        var events = new DirectExchange(EXCHANGE, true, false);
        var dead = new DirectExchange(DEAD, true, false);
        var stock = queue(STOCK_QUEUE);
        var notification = queue(NOTIFICATION_QUEUE);
        var stockDead = QueueBuilder.durable(STOCK_QUEUE + ".dead").quorum().build();
        var notificationDead = QueueBuilder.durable(NOTIFICATION_QUEUE + ".dead").quorum().build();
        return new Declarables(events, dead, stock, notification, stockDead, notificationDead,
                BindingBuilder.bind(stock).to(events).with("inventory.stock_available.v1"),
                BindingBuilder.bind(notification).to(events).with("notification.requested.v1"),
                BindingBuilder.bind(stockDead).to(dead).with(STOCK_QUEUE),
                BindingBuilder.bind(notificationDead).to(dead).with(NOTIFICATION_QUEUE));
    }
    private Queue queue(String name) {
        return QueueBuilder.durable(name).quorum().withArgument("x-delivery-limit", 5)
                .withArgument("x-dead-letter-strategy", "at-least-once")
                .withArgument("x-overflow", "reject-publish")
                .deadLetterExchange(DEAD).deadLetterRoutingKey(name).build();
    }
}
