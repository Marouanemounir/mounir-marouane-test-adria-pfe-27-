package ma.enset.transactionservice.integration;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
public record AccountOperationResponse(UUID id, String type, UUID sourceAccountId, UUID destinationAccountId,
        BigDecimal amount, String currency, BigDecimal sourceBalanceBefore, BigDecimal sourceBalanceAfter,
        BigDecimal destinationBalanceAfter, Instant createdAt) {}
