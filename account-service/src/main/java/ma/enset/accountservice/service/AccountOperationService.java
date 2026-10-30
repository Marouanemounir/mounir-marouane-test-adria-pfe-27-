package ma.enset.accountservice.service;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import ma.enset.accountservice.domain.*;
import ma.enset.accountservice.repository.*;
import ma.enset.accountservice.web.rest.dto.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;
import java.math.BigDecimal;
import java.util.*;

@Service
@Validated
public class AccountOperationService {
    private final AccountRepository accounts;
    private final AccountOperationRepository operations;
    public AccountOperationService(AccountRepository accounts, AccountOperationRepository operations) {
        this.accounts = accounts; this.operations = operations;
    }

    @Transactional(readOnly = true)
    public AccountOperationResponse get(UUID id) {
        return AccountOperationResponse.from(operations.findById(id)
                .orElseThrow(() -> new AccountNotFoundException(id)));
    }

    @Transactional
    public AccountOperationResponse execute(@NotNull UUID id, @NotNull @Valid AccountOperationRequest request) {
        var existing = operations.findById(id);
        if (existing.isPresent()) {
            AccountOperationResponse receipt = AccountOperationResponse.from(existing.get());
            if (!receipt.type().equals(request.type()) || !receipt.sourceAccountId().equals(request.sourceAccountId())
                    || !Objects.equals(receipt.destinationAccountId(), request.destinationAccountId())
                    || receipt.amount().compareTo(request.amount()) != 0 || !receipt.currency().equals(request.currency())) {
                throw new AccountConflictException("Idempotency key already used with a different operation");
            }
            return receipt;
        }
        boolean transfer = request.type().equals("TRANSFER");
        if (transfer && (request.destinationAccountId() == null
                || request.sourceAccountId().equals(request.destinationAccountId()))) {
            throw new IllegalArgumentException("Transfer requires two distinct accounts");
        }
        if (!transfer && request.destinationAccountId() != null) {
            throw new IllegalArgumentException("Destination is only allowed for a transfer");
        }
        // Flush the unique key BEFORE locking or changing balances. A concurrent duplicate rolls back.
        AccountOperation operation = operations.saveAndFlush(new AccountOperation(id, request.type(),
                request.sourceAccountId(), request.destinationAccountId(), request.amount(), request.currency()));
        List<UUID> ids = new ArrayList<>(List.of(request.sourceAccountId()));
        if (transfer) ids.add(request.destinationAccountId());
        ids.sort(Comparator.naturalOrder()); // Identical order even for opposite transfers.
        Map<UUID, Account> locked = new HashMap<>();
        for (UUID accountId : ids) {
            Account account = accounts.findByIdForUpdate(accountId)
                    .orElseThrow(() -> new AccountNotFoundException(accountId));
            if (account.getStatus() != AccountStatus.ACTIVE) throw new AccountConflictException("Account is blocked");
            if (!account.getCurrency().equals(request.currency())) throw new AccountConflictException("Incompatible currency");
            locked.put(accountId, account);
        }
        Account source = locked.get(request.sourceAccountId());
        BigDecimal before = BigDecimal.valueOf(source.getBalance());
        BigDecimal after = request.type().equals("CREDIT")
                ? before.add(request.amount()) : before.subtract(request.amount());
        if (after.signum() < 0) throw new AccountConflictException("Insufficient balance");
        source.setBalance(exactLegacyBalance(after));
        BigDecimal destinationAfter = null;
        if (transfer) {
            Account destination = locked.get(request.destinationAccountId());
            destinationAfter = BigDecimal.valueOf(destination.getBalance()).add(request.amount());
            destination.setBalance(exactLegacyBalance(destinationAfter));
        }
        operation.complete(before, after, destinationAfter);
        return AccountOperationResponse.from(operation);
    }

    private double exactLegacyBalance(BigDecimal value) {
        double result = value.doubleValue();
        // Account's pre-existing double model is preserved; reject unrepresentable mutations.
        if (!Double.isFinite(result) || BigDecimal.valueOf(result).compareTo(value) != 0
                || value.precision() - value.scale() > 36 || Math.max(0, value.stripTrailingZeros().scale()) > 2) {
            throw new IllegalArgumentException("Balance cannot be represented exactly by the existing account model");
        }
        return result;
    }
}
