package ma.enset.accountservice.domain;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** Immutable receipt committed in the SAME database transaction as the balances. */
@Entity
@Table(name = "account_operations")
public class AccountOperation {
    @Id private UUID id;
    @Version private Long version;
    @Column(nullable = false) private String type;
    @Column(nullable = false) private UUID sourceAccountId;
    private UUID destinationAccountId;
    @Column(nullable = false, precision = 19, scale = 2) private BigDecimal amount;
    @Column(nullable = false, length = 3) private String currency;
    @Column(precision = 38, scale = 2) private BigDecimal sourceBalanceBefore;
    @Column(precision = 38, scale = 2) private BigDecimal sourceBalanceAfter;
    @Column(precision = 38, scale = 2) private BigDecimal destinationBalanceAfter;
    @Column(nullable = false) private Instant createdAt;

    protected AccountOperation() {}
    public AccountOperation(UUID id, String type, UUID sourceAccountId, UUID destinationAccountId,
            BigDecimal amount, String currency) {
        this.id = id; this.type = type; this.sourceAccountId = sourceAccountId;
        this.destinationAccountId = destinationAccountId; this.amount = amount.setScale(2);
        this.currency = currency; this.createdAt = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
    }
    public void complete(BigDecimal before, BigDecimal after, BigDecimal destination) {
        sourceBalanceBefore = before.setScale(2); sourceBalanceAfter = after.setScale(2);
        destinationBalanceAfter = destination == null ? null : destination.setScale(2);
    }
    public UUID getId() { return id; }
    public String getType() { return type; }
    public UUID getSourceAccountId() { return sourceAccountId; }
    public UUID getDestinationAccountId() { return destinationAccountId; }
    public BigDecimal getAmount() { return amount; }
    public String getCurrency() { return currency; }
    public BigDecimal getSourceBalanceBefore() { return sourceBalanceBefore; }
    public BigDecimal getSourceBalanceAfter() { return sourceBalanceAfter; }
    public BigDecimal getDestinationBalanceAfter() { return destinationBalanceAfter; }
    public Instant getCreatedAt() { return createdAt; }
}
