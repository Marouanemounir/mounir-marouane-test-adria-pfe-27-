package ma.enset.transactionservice.integration;
import java.math.BigDecimal;
import java.util.UUID;
public record AccountClientResponse(UUID id, String customerReference, String status, String currency, BigDecimal balance) {}
