package ma.enset.accountservice.web.rest.dto;

import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.util.UUID;

public record AccountOperationRequest(
        @NotBlank @Pattern(regexp = "DEBIT|CREDIT|TRANSFER") String type,
        @NotNull UUID sourceAccountId,
        UUID destinationAccountId,
        @NotNull @DecimalMin(value = "0", inclusive = false) @Digits(integer = 15, fraction = 2) BigDecimal amount,
        @NotBlank @Pattern(regexp = "[A-Z]{3}") String currency) {}
