package ma.enset.accountservice.web.rest.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import ma.enset.accountservice.domain.AccountStatus;

public record CreateAccountRequest(
        @NotBlank @Size(max = 255) String customerReference,
        AccountStatus status,
        @NotBlank @Pattern(regexp = "[A-Z]{3}") String currency,
        @NotNull @DecimalMin("0.0") Double balance) {
}
