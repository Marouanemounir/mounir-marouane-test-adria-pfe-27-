package ma.enset.transactionservice.web.rest.dto;
import java.math.BigDecimal;
import java.util.UUID;
public record TransferResponse(UUID transferId, UUID sourceAccountId, UUID destinationAccountId,
        BigDecimal amount, String currency, BigDecimal sourceBalance, BigDecimal destinationBalance,
        UUID debitTransactionId, UUID creditTransactionId) {}
