package ma.enset.transactionservice.integration;
import feign.FeignException;
import io.github.resilience4j.circuitbreaker.*;
import ma.enset.transactionservice.service.exception.*;
import org.springframework.stereotype.Component;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

@Component
public class AccountGateway {
    private final AccountClient client;
    private final CircuitBreaker circuit;
    public AccountGateway(AccountClient client, CircuitBreakerRegistry registry) {
        this.client = client; this.circuit = registry.circuitBreaker("accountService");
    }
    public AccountClientResponse account(UUID id) { return call(() -> client.getAccount(id)); }
    public BalanceResponse balance(UUID id) { return call(() -> client.getBalance(id)); }
    public Optional<AccountOperationResponse> receipt(UUID id) {
        return call(() -> {
            try {
                AccountOperationResponse receipt = client.getOperation(id);
                if (receipt == null) throw new AccountUnavailableException("Empty account operation response");
                return Optional.of(receipt);
            }
            catch (FeignException.NotFound missing) { return Optional.empty(); }
        });
    }
    public AccountOperationResponse execute(UUID id, AccountOperationRequest request) {
        return call(() -> "TRANSFER".equals(request.type()) ? client.transfer(id, request) : client.execute(id, request));
    }
    private <T> T call(Supplier<T> action) {
        try {
            return circuit.executeSupplier(() -> {
                try { return action.get(); }
                catch (FeignException e) {
                    if (e.status() == 404) throw new BusinessException(404, "Account not found");
                    if (e.status() == 409) throw new BusinessException(409, "Account conflict: " + e.contentUTF8());
                    if (e.status() == 400) throw new BusinessException(400, "Account rejected invalid operation");
                    throw new AccountUnavailableException("Account Service unavailable; no replacement balance provided", e);
                }
            });
        } catch (CallNotPermittedException e) {
            throw new AccountUnavailableException("Account Service circuit breaker is OPEN", e);
        }
    }
}
