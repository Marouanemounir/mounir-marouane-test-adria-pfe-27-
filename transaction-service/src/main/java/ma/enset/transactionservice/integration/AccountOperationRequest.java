package ma.enset.transactionservice.integration;
import java.math.BigDecimal;
import java.util.UUID;
public record AccountOperationRequest(String type, UUID sourceAccountId, UUID destinationAccountId,
        BigDecimal amount, String currency) {}
