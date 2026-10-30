package ma.enset.transactionservice.web.rest.dto;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.util.UUID;
public record CreateTransactionRequest(
        @NotNull(message = "Account identifier is required") UUID accountId,
        @NotNull(message = "Amount is required") @DecimalMin(value = "0", inclusive = false, message = "Amount must be strictly positive")
        @Digits(integer = 15, fraction = 2, message = "Amount supports up to 15 integer digits and 2 decimal places") BigDecimal amount,
        @NotBlank(message = "Currency is required") @Pattern(regexp = "[A-Z]{3}", message = "Currency must contain three uppercase letters") String currency,
        @NotBlank(message = "Merchant category is required") @Size(max = 100) String merchantCategory,
        @NotBlank(message = "Country is required") @Size(max = 100) String country) {}
