package ma.enset.transactionservice.domain;
import jakarta.persistence.*;
import ma.enset.transactionservice.integration.AccountOperationRequest;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;

@Entity
@Table(name = "transaction_operations")
public class TransactionOperation {
    @Id private UUID id;
    @Version private Long version;
    @Column(nullable = false) private String type;
    @Column(nullable = false) private UUID sourceAccountId;
    private UUID destinationAccountId;
    @Column(nullable = false, precision = 19, scale = 2) private BigDecimal amount;
    @Column(nullable = false, length = 3) private String currency;
    @Column(nullable = false, length = 100) private String merchantCategory;
    @Column(nullable = false, length = 100) private String country;
    @Column(nullable = false) private Instant createdAt;
    @Column(nullable = false) private String state;
    private UUID debitTransactionId;
    private UUID creditTransactionId;
    @Column(precision = 38, scale = 2) private BigDecimal sourceBalance;
    @Column(precision = 38, scale = 2) private BigDecimal destinationBalance;
    protected TransactionOperation() {}
    public TransactionOperation(UUID id, String type, UUID source, UUID destination, BigDecimal amount,
            String currency, String merchantCategory, String country) {
        this.id = id; this.type = type; this.sourceAccountId = source; this.destinationAccountId = destination;
        this.amount = amount; this.currency = currency; this.merchantCategory = merchantCategory;
        this.country = country; this.createdAt = Instant.now(); this.state = "PENDING";
    }
    public boolean sameRequest(TransactionOperation o) {
        return type.equals(o.type) && sourceAccountId.equals(o.sourceAccountId)
                && Objects.equals(destinationAccountId, o.destinationAccountId) && amount.compareTo(o.amount) == 0
                && currency.equals(o.currency) && merchantCategory.equals(o.merchantCategory) && country.equals(o.country);
    }
    public AccountOperationRequest remoteRequest() {
        return new AccountOperationRequest(type, sourceAccountId, destinationAccountId, amount, currency);
    }
    public void complete(UUID debit, UUID credit, BigDecimal sourceBalance, BigDecimal destinationBalance) {
        this.debitTransactionId = debit; this.creditTransactionId = credit; this.sourceBalance = sourceBalance;
        this.destinationBalance = destinationBalance; this.state = "COMPLETED";
    }
    public UUID getId() { return id; }
    public String getType() { return type; }
    public UUID getSourceAccountId() { return sourceAccountId; }
    public UUID getDestinationAccountId() { return destinationAccountId; }
    public BigDecimal getAmount() { return amount; }
    public String getCurrency() { return currency; }
    public String getMerchantCategory() { return merchantCategory; }
    public String getCountry() { return country; }
    public String getState() { return state; }
    public Instant getCreatedAt() { return createdAt; }
    public UUID getDebitTransactionId() { return debitTransactionId; }
    public UUID getCreditTransactionId() { return creditTransactionId; }
    public BigDecimal getSourceBalance() { return sourceBalance; }
    public BigDecimal getDestinationBalance() { return destinationBalance; }
}
