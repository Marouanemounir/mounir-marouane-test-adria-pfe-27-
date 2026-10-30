package ma.enset.accountservice.web.rest.dto;
import ma.enset.accountservice.domain.AccountOperation;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
public record AccountOperationResponse(UUID id, String type, UUID sourceAccountId, UUID destinationAccountId,
        BigDecimal amount, String currency, BigDecimal sourceBalanceBefore, BigDecimal sourceBalanceAfter,
        BigDecimal destinationBalanceAfter, Instant createdAt) {
    public static AccountOperationResponse from(AccountOperation o) {
        return new AccountOperationResponse(o.getId(), o.getType(), o.getSourceAccountId(),
                o.getDestinationAccountId(), o.getAmount(), o.getCurrency(), o.getSourceBalanceBefore(),
                o.getSourceBalanceAfter(), o.getDestinationBalanceAfter(), o.getCreatedAt());
    }
}
