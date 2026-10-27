package dev.stockping.demand.grpc;

import dev.stockping.contracts.demand.v1.*;
import dev.stockping.demand.exception.DemandException;
import dev.stockping.demand.messaging.CustomerMessageService;
import dev.stockping.demand.service.PhoneNormalizer;
import io.grpc.*;
import io.grpc.stub.StreamObserver;
import java.time.Instant;
import java.util.UUID;
import org.slf4j.*;
import org.springframework.stereotype.Component;

@Component
public class DemandGrpcService extends DemandServiceGrpc.DemandServiceImplBase {
    private static final Logger LOG = LoggerFactory.getLogger(DemandGrpcService.class);
    private final CustomerMessageService messages;
    private final PhoneNormalizer phones;
    public DemandGrpcService(CustomerMessageService messages, PhoneNormalizer phones) { this.messages = messages; this.phones = phones; }
    @Override public void processCustomerMessage(ProcessCustomerMessageRequest request, StreamObserver<ProcessCustomerMessageResponse> observer) {
        String correlation = null;
        try {
            UUID merchant = uuid(request.getMerchantId());
            correlation = uuid(request.getCorrelationId()).toString();
            String phone = phones.normalize(request.getCustomerPhone());
            if (!phone.equals(request.getCustomerPhone()) || request.getMessageText().isBlank()
                    || request.getMessageText().codePointCount(0, request.getMessageText().length()) > 4096
                    || request.getCustomerDisplayName().length() > 200 || request.getProviderMessageId().isBlank()
                    || request.getProviderMessageId().length() > 512) throw new IllegalArgumentException();
            Instant received = Instant.parse(request.getReceivedAt());
            if (received.isAfter(Instant.now().plusSeconds(300))) throw new IllegalArgumentException();
            if (Context.current().isCancelled()) { observer.onError(Status.CANCELLED.asRuntimeException()); return; }
            try (var ignored = MDC.putCloseable("request.id", correlation)) {
                var reply = messages.process(new CustomerMessageService.Message(merchant, phone, request.getCustomerDisplayName(),
                        request.getProviderMessageId(), request.getMessageText(), received, correlation));
                LOG.info("Customer command processed requestId={} merchantId={} purpose={}", correlation, merchant, reply.purpose());
                observer.onNext(ProcessCustomerMessageResponse.newBuilder().setAction(reply.sendText() ? ReplyAction.SEND_TEXT : ReplyAction.NO_REPLY)
                        .setReplyText(reply.text()).setPurpose(reply.purpose()).setCorrelationId(correlation).build());
                observer.onCompleted();
            }
        } catch (IllegalArgumentException | java.time.DateTimeException e) {
            observer.onError(Status.INVALID_ARGUMENT.withDescription("Invalid normalized customer message").asRuntimeException());
        } catch (DemandException e) {
            observer.onError((e.status() == 404 ? Status.NOT_FOUND : Status.FAILED_PRECONDITION).withDescription("Message cannot be processed").asRuntimeException());
        } catch (Exception e) {
            LOG.error("Customer command failed requestId={} type={}", correlation, e.getClass().getSimpleName());
            observer.onError(Status.INTERNAL.withDescription("Message processing failed").asRuntimeException());
        }
    }
    private static UUID uuid(String value) {
        UUID parsed = UUID.fromString(value);
        if (!parsed.toString().equals(value)) throw new IllegalArgumentException();
        return parsed;
    }
}
