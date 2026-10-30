package ma.enset.transactionservice.domain;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "bank_transactions", indexes = @Index(name = "idx_transaction_account", columnList = "accountId"),
        uniqueConstraints = @UniqueConstraint(columnNames = {"operationId", "direction"}))
public class Transaction {
    @Id @GeneratedValue(strategy = GenerationType.UUID) private UUID id;
    @Column(nullable = false) private UUID accountId;
    @Column(nullable = false, precision = 19, scale = 2) private BigDecimal amount;
    @Column(nullable = false, length = 3) private String currency;
    @Column(nullable = false, length = 100) private String merchantCategory;
    @Column(nullable = false, length = 100) private String country;
    @Column(nullable = false) private Instant createdAt;
    @Column(nullable = false) private UUID operationId;
    private UUID transferId;
    @Column(nullable = false) private String direction;
    protected Transaction() {}
    public Transaction(UUID accountId, BigDecimal amount, String currency, String merchantCategory, String country,
            UUID operationId, UUID transferId, String direction) {
        this.accountId = accountId; this.amount = amount; this.currency = currency;
        this.merchantCategory = merchantCategory; this.country = country; this.createdAt = Instant.now();
        this.operationId = operationId; this.transferId = transferId; this.direction = direction;
    }
    public UUID getId() { return id; }
    public UUID getAccountId() { return accountId; }
    public BigDecimal getAmount() { return amount; }
    public String getCurrency() { return currency; }
    public String getMerchantCategory() { return merchantCategory; }
    public String getCountry() { return country; }
    public Instant getCreatedAt() { return createdAt; }
    public UUID getOperationId() { return operationId; }
    public UUID getTransferId() { return transferId; }
    public String getDirection() { return direction; }
}
