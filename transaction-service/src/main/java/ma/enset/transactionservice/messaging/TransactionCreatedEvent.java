package ma.enset.transactionservice.messaging;

import com.fasterxml.jackson.annotation.JsonFormat;
import ma.enset.transactionservice.domain.Transaction;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record TransactionCreatedEvent(UUID eventId, String eventType, UUID transactionId,
        UUID accountId, BigDecimal amount, String currency,
        @JsonFormat(shape = JsonFormat.Shape.STRING) Instant occurredAt) {
    public TransactionCreatedEvent {
        if (!"TransactionCreated".equals(eventType)) {
            throw new IllegalArgumentException("eventType must be TransactionCreated");
        }
    }

    public static TransactionCreatedEvent from(Transaction transaction) {
        return new TransactionCreatedEvent(UUID.randomUUID(), "TransactionCreated", transaction.getId(),
                transaction.getAccountId(), transaction.getAmount(), transaction.getCurrency(), transaction.getCreatedAt());
    }
}
