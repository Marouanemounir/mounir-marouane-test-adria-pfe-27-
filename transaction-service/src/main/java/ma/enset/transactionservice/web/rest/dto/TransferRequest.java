package ma.enset.transactionservice.web.rest.dto;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.util.UUID;
public record TransferRequest(
        @NotNull(message = "Source account identifier is required") UUID sourceAccountId,
        @NotNull(message = "Destination account identifier is required") UUID destinationAccountId,
        @NotNull(message = "Amount is required") @DecimalMin(value = "0", inclusive = false, message = "Amount must be strictly positive")
        @Digits(integer = 15, fraction = 2) BigDecimal amount,
        @NotBlank(message = "Currency is required") @Pattern(regexp = "[A-Z]{3}", message = "Currency must contain three uppercase letters") String currency) {}
