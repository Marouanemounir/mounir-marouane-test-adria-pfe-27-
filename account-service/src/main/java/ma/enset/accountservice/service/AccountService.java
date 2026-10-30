package ma.enset.accountservice.service;

import ma.enset.accountservice.domain.Account;
import ma.enset.accountservice.domain.AccountStatus;
import ma.enset.accountservice.repository.AccountRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Currency;
import java.util.List;
import java.util.UUID;

@Service
@Transactional(readOnly = true)
public class AccountService {
    private final AccountRepository repository;

    public AccountService(AccountRepository repository) {
        this.repository = repository;
    }

    @Transactional
    public Account createAccount(String customerReference, AccountStatus status, String currency, double balance) {
        if (customerReference == null || customerReference.isBlank() || customerReference.length() > 255) {
            throw new IllegalArgumentException("Customer reference must contain between 1 and 255 characters");
        }
        if (currency == null || !currency.matches("[A-Z]{3}")) {
            throw new IllegalArgumentException("Currency must be a three-letter ISO 4217 code");
        }
        try {
            Currency.getInstance(currency);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Unknown ISO 4217 currency: " + currency);
        }
        if (!Double.isFinite(balance) || balance < 0) {
            throw new IllegalArgumentException("Initial balance must be finite and non-negative");
        }
        return repository.save(new Account(customerReference, status == null ? AccountStatus.ACTIVE : status,
                currency, balance));
    }

    public List<Account> getAllAccounts() {
        return repository.findAll();
    }

    public Account getAccountById(UUID id) {
        return repository.findById(id).orElseThrow(() -> new AccountNotFoundException(id));
    }

    public List<Account> getAccountsByCustomerReference(String reference) {
        return repository.findByCustomerReference(reference);
    }

    public double getBalance(UUID id) {
        return getAccountById(id).getBalance();
    }

    @Transactional
    public Account debit(UUID id, BigDecimal amount) {
        validateAmount(amount);
        Account account = getAccountForUpdate(id);
        requireActive(account);
        BigDecimal result = BigDecimal.valueOf(account.getBalance()).subtract(amount);
        if (result.signum() < 0) {
            throw new AccountConflictException("Insufficient balance");
        }
        account.setBalance(toFiniteBalance(result));
        return account;
    }

    @Transactional
    public Account credit(UUID id, BigDecimal amount) {
        validateAmount(amount);
        Account account = getAccountForUpdate(id);
        requireActive(account);
        account.setBalance(toFiniteBalance(BigDecimal.valueOf(account.getBalance()).add(amount)));
        return account;
    }

    @Transactional
    public Account updateStatus(UUID id, AccountStatus status) {
        if (status == null) {
            throw new IllegalArgumentException("Status is required");
        }
        // Use the balance lock too, so blocking is serialized with debit and credit operations.
        Account account = getAccountForUpdate(id);
        account.setStatus(status);
        return account;
    }

    private Account getAccountForUpdate(UUID id) {
        return repository.findByIdForUpdate(id).orElseThrow(() -> new AccountNotFoundException(id));
    }

    private void requireActive(Account account) {
        if (account.getStatus() == AccountStatus.BLOCKED) {
            throw new AccountConflictException("Account is blocked");
        }
    }

    private void validateAmount(BigDecimal amount) {
        if (amount == null || amount.signum() <= 0) {
            throw new IllegalArgumentException("Amount must be strictly positive");
        }
        if (!Double.isFinite(amount.doubleValue()) || amount.doubleValue() == 0) {
            throw new IllegalArgumentException("Amount is outside the supported double range");
        }
    }

    private double toFiniteBalance(BigDecimal balance) {
        double result = balance.doubleValue();
        if (!Double.isFinite(result)) {
            throw new IllegalArgumentException("Resulting balance is outside the supported double range");
        }
        return result;
    }
}
