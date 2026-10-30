package ma.enset.transactionservice.web.rest.dto;
import ma.enset.transactionservice.domain.Transaction;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
public record TransactionResponse(UUID id, UUID accountId, BigDecimal amount, String currency,
        String merchantCategory, String country, Instant createdAt, UUID operationId, UUID transferId, String direction) {
    public static TransactionResponse from(Transaction t) {
        return new TransactionResponse(t.getId(), t.getAccountId(), t.getAmount(), t.getCurrency(),
                t.getMerchantCategory(), t.getCountry(), t.getCreatedAt(), t.getOperationId(), t.getTransferId(), t.getDirection());
    }
}
