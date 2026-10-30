package ma.enset.transactionservice.service;

import ma.enset.transactionservice.domain.*;
import ma.enset.transactionservice.integration.AccountOperationResponse;
import ma.enset.transactionservice.repository.*;
import ma.enset.transactionservice.service.exception.BusinessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.UUID;

/** Short local transactions only: never hold a database transaction across HTTP calls. */
@Service
public class OperationStore {
    private final TransactionOperationRepository operations;
    private final TransactionRepository transactions;
    public OperationStore(TransactionOperationRepository operations, TransactionRepository transactions) {
        this.operations = operations; this.transactions = transactions;
    }
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.REQUIRES_NEW)
    public TransactionOperation prepare(TransactionOperation candidate) {
        var existing = operations.findById(candidate.getId());
        if (existing.isPresent()) {
            if (!existing.get().sameRequest(candidate)) {
                throw new BusinessException(409, "Idempotency-Key already used with a different request");
            }
            return existing.get();
        }
        return operations.saveAndFlush(candidate);
    }
    @Transactional(readOnly = true)
    public TransactionOperation get(UUID id) {
        return operations.findById(id).orElseThrow(() -> new BusinessException(404, "Operation not found: " + id));
    }
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.REQUIRES_NEW)
    public TransactionOperation complete(UUID id, AccountOperationResponse receipt) {
        TransactionOperation o = operations.findForUpdate(id)
                .orElseThrow(() -> new BusinessException(404, "Operation not found: " + id));
        if ("COMPLETED".equals(o.getState())) return o;
        boolean transfer = "TRANSFER".equals(o.getType());
        Transaction debit = transactions.saveAndFlush(new Transaction(o.getSourceAccountId(), o.getAmount(),
                o.getCurrency(), o.getMerchantCategory(), o.getCountry(), id, transfer ? id : null, "DEBIT"));
        UUID creditId = null;
        if (transfer) {
            Transaction credit = transactions.saveAndFlush(new Transaction(o.getDestinationAccountId(), o.getAmount(),
                    o.getCurrency(), o.getMerchantCategory(), o.getCountry(), id, id, "CREDIT"));
            creditId = credit.getId();
        }
        o.complete(debit.getId(), creditId, receipt.sourceBalanceAfter(), receipt.destinationBalanceAfter());
        operations.flush();
        return o;
    }
}
