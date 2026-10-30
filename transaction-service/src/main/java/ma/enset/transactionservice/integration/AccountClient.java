package ma.enset.transactionservice.integration;
import ma.enset.transactionservice.config.AccountFeignConfiguration;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.*;
import java.util.UUID;

// Empty URL selects Eureka; an explicit URL keeps the standalone HTTP mode.
@FeignClient(name = "account-service", url = "${bank.services.account-url:}", configuration = AccountFeignConfiguration.class)
public interface AccountClient {
    @GetMapping("/api/accounts/{id}") AccountClientResponse getAccount(@PathVariable("id") UUID id);
    @GetMapping("/api/accounts/{id}/balance") BalanceResponse getBalance(@PathVariable("id") UUID id);
    // Existing legacy API: intentionally NOT used for retryable banking workflows.
    @PatchMapping("/api/accounts/{id}/debit") AccountClientResponse debit(@PathVariable("id") UUID id, @RequestBody AmountRequest request);
    @PatchMapping("/api/accounts/{id}/credit") AccountClientResponse credit(@PathVariable("id") UUID id, @RequestBody AmountRequest request);
    @GetMapping("/api/accounts/operations/{id}") AccountOperationResponse getOperation(@PathVariable("id") UUID id);
    @PutMapping("/api/accounts/operations/{id}") AccountOperationResponse execute(@PathVariable("id") UUID id, @RequestBody AccountOperationRequest request);
    @PostMapping("/api/accounts/transfers") AccountOperationResponse transfer(@RequestHeader("Idempotency-Key") UUID id, @RequestBody AccountOperationRequest request);
}
