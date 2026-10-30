package ma.enset.transactionservice.integration;
import java.math.BigDecimal;
import java.util.UUID;
public record BalanceResponse(UUID id, BigDecimal balance) {}
