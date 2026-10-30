package ma.enset.transactionservice.messaging;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
public class TransactionEventPublisher {
    public static final String TOPIC = "bank.transactions.created";
    private static final Logger log = LoggerFactory.getLogger(TransactionEventPublisher.class);
    private final KafkaTemplate<String, TransactionCreatedEvent> kafka;

    public TransactionEventPublisher(KafkaTemplate<String, TransactionCreatedEvent> kafka) {
        this.kafka = kafka;
    }

    // The banking commit has succeeded. Durable delivery would require a transactional outbox.
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void publish(TransactionCreatedEvent event) {
        try {
            kafka.send(TOPIC, event.accountId().toString(), event).whenComplete((result, error) -> {
                if (error != null) {
                    log.error("Kafka publication failed after banking commit: eventId={} transactionId={}",
                            event.eventId(), event.transactionId(), error);
                } else {
                    log.info("Kafka published eventId={} transactionId={} topic={} partition={} offset={}",
                            event.eventId(), event.transactionId(), TOPIC,
                            result.getRecordMetadata().partition(), result.getRecordMetadata().offset());
                }
            });
        } catch (RuntimeException error) {
            // A Kafka failure must not report a committed banking operation as failed.
            log.error("Kafka publication failed after banking commit: eventId={} transactionId={}",
                    event.eventId(), event.transactionId(), error);
        }
    }
}
