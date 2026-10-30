package ma.enset.transactionservice.messaging;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
public class TransactionEventConsumer {
    private static final Logger log = LoggerFactory.getLogger(TransactionEventConsumer.class);

    @KafkaListener(topics = TransactionEventPublisher.TOPIC)
    public void consume(TransactionCreatedEvent event) {
        log.info("Kafka received eventId={} transactionId={} accountId={} amount={} currency={} occurredAt={}",
                event.eventId(), event.transactionId(), event.accountId(), event.amount(),
                event.currency(), event.occurredAt());
    }
}
