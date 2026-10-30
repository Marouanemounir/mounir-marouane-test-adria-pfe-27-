package ma.enset.transactionservice.config;
import jakarta.validation.constraints.*;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;
import java.math.BigDecimal;
@Validated
@ConfigurationProperties("bank.transaction")
public record TransactionProperties(@NotNull @DecimalMin(value = "0", inclusive = false) BigDecimal maxAmount) {}
