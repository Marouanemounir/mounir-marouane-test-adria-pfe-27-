package ma.enset.transactionservice.web.rest.dto;
import ma.enset.transactionservice.domain.TransactionOperation;
import java.time.Instant;
import java.util.UUID;
public record OperationResponse(UUID operationId, String type, String state, UUID debitTransactionId,
        UUID creditTransactionId, Instant createdAt) {
    public static OperationResponse from(TransactionOperation o) {
        return new OperationResponse(o.getId(), o.getType(), o.getState(), o.getDebitTransactionId(),
                o.getCreditTransactionId(), o.getCreatedAt());
    }
}
