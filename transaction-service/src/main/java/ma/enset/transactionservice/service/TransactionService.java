package ma.enset.transactionservice.service;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import ma.enset.transactionservice.config.TransactionProperties;
import ma.enset.transactionservice.domain.TransactionOperation;
import ma.enset.transactionservice.integration.*;
import ma.enset.transactionservice.repository.TransactionRepository;
import ma.enset.transactionservice.service.exception.*;
import ma.enset.transactionservice.web.rest.dto.*;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.validation.annotation.Validated;
import java.math.BigDecimal;
import java.util.*;

@Service
@Validated
public class TransactionService {
    private final TransactionRepository transactions;
    private final OperationStore store;
    private final AccountGateway accounts;
    private final TransactionProperties properties;
    public TransactionService(TransactionRepository transactions, OperationStore store,
            AccountGateway accounts, TransactionProperties properties) {
        this.transactions = transactions; this.store = store; this.accounts = accounts; this.properties = properties;
    }

    public TransactionResponse createTransaction(@NotNull @Valid CreateTransactionRequest request, @NotNull UUID operationId) {
        validateAmount(request.amount());
        var candidate = new TransactionOperation(operationId, "DEBIT", request.accountId(), null,
                request.amount(), request.currency(), request.merchantCategory(), request.country());
        TransactionOperation result = process(store.prepare(candidate));
        return getTransaction(result.getDebitTransactionId());
    }

    public TransferResponse transfer(@NotNull @Valid TransferRequest request, @NotNull UUID operationId) {
        validateAmount(request.amount());
        if (request.sourceAccountId().equals(request.destinationAccountId())) {
            throw new BusinessException(400, "Source and destination accounts must be different");
        }
        var candidate = new TransactionOperation(operationId, "TRANSFER", request.sourceAccountId(),
                request.destinationAccountId(), request.amount(), request.currency(), "TRANSFER", "INTERNAL");
        TransactionOperation result = process(store.prepare(candidate));
        return new TransferResponse(result.getId(), result.getSourceAccountId(), result.getDestinationAccountId(),
                result.getAmount(), result.getCurrency(), result.getSourceBalance(), result.getDestinationBalance(),
                result.getDebitTransactionId(), result.getCreditTransactionId());
    }

    public List<TransactionResponse> getTransactions() {
        return transactions.findAll().stream().map(TransactionResponse::from).toList();
    }
    public TransactionResponse getTransaction(UUID id) {
        return TransactionResponse.from(transactions.findById(id)
                .orElseThrow(() -> new BusinessException(404, "Transaction not found: " + id)));
    }
    public List<TransactionResponse> getHistory(UUID accountId) {
        return transactions.findByAccountId(accountId).stream().map(TransactionResponse::from).toList();
    }
    public OperationResponse operation(UUID id) { return OperationResponse.from(store.get(id)); }
    public OperationResponse reconcile(UUID id) { return OperationResponse.from(process(store.get(id))); }

    private void validateAmount(BigDecimal amount) {
        if (amount == null || amount.signum() <= 0) throw new BusinessException(400, "Amount must be strictly positive");
        if (amount.compareTo(properties.maxAmount()) > 0) {
            throw new BusinessException(400, "Amount exceeds configured maximum of " + properties.maxAmount());
        }
    }

    private TransactionOperation process(TransactionOperation o) {
        if ("COMPLETED".equals(o.getState())) return o;
        AccountOperationResponse receipt;
        try {
            Optional<AccountOperationResponse> previous = accounts.receipt(o.getId());
            if (previous.isPresent()) {
                receipt = previous.get(); // Replay does not recheck a balance/status that has changed since the debit.
            } else {
                checkAccount(o.getSourceAccountId(), o.getCurrency());
                if (o.getDestinationAccountId() != null) checkAccount(o.getDestinationAccountId(), o.getCurrency());
                BalanceResponse balance = accounts.balance(o.getSourceAccountId());
                if (balance == null || !o.getSourceAccountId().equals(balance.id()) || balance.balance() == null) {
                    throw new AccountUnavailableException("Invalid balance response");
                }
                if (balance.balance().compareTo(o.getAmount()) < 0) throw new BusinessException(409, "Insufficient balance");
                try {
                    receipt = accounts.execute(o.getId(), o.remoteRequest());
                } catch (AccountUnavailableException ambiguous) {
                    // A timeout / 500 may happen AFTER commit. Never infer rollback or compensate blindly.
                    receipt = accounts.receipt(o.getId()).orElseThrow(() -> ambiguous);
                } catch (BusinessException conflict) {
                    // A concurrent duplicate may have committed while our request was rejected.
                    if (conflict.getStatus() != 409) throw conflict;
                    receipt = accounts.receipt(o.getId()).orElseThrow(() -> conflict);
                }
            }
            verifyReceipt(o, receipt);
            return store.complete(o.getId(), receipt);
        } catch (AccountUnavailableException | DataAccessException | jakarta.persistence.PersistenceException
                | org.springframework.transaction.TransactionException e) {
            throw new RecoveryRequiredException(o.getId(), e);
        }
    }

    private void checkAccount(UUID id, String currency) {
        AccountClientResponse account = accounts.account(id);
        if (account == null || !id.equals(account.id())) throw new AccountUnavailableException("Invalid account response");
        if (!"ACTIVE".equals(account.status())) throw new BusinessException(409, "Account is blocked");
        if (!currency.equals(account.currency())) throw new BusinessException(409, "Incompatible currency");
    }

    private void verifyReceipt(TransactionOperation o, AccountOperationResponse r) {
        if (r == null || !o.getId().equals(r.id()) || !o.getType().equals(r.type())
                || !o.getSourceAccountId().equals(r.sourceAccountId())
                || !Objects.equals(o.getDestinationAccountId(), r.destinationAccountId())
                || r.amount() == null || o.getAmount().compareTo(r.amount()) != 0
                || !o.getCurrency().equals(r.currency()) || r.sourceBalanceBefore() == null
                || r.sourceBalanceAfter() == null || r.sourceBalanceAfter().signum() < 0
                || r.sourceBalanceBefore().subtract(o.getAmount()).compareTo(r.sourceBalanceAfter()) != 0
                || ("TRANSFER".equals(o.getType()) && (r.destinationBalanceAfter() == null
                    || r.destinationBalanceAfter().signum() < 0))) {
            throw new AccountUnavailableException("Inconsistent account operation receipt; manual reconciliation required");
        }
    }
}
