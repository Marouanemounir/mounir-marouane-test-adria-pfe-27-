package ma.enset.transactionservice.service.exception;
import java.util.UUID;
public class RecoveryRequiredException extends RuntimeException {
    private final UUID operationId;
    public RecoveryRequiredException(UUID operationId, Throwable cause) {
        super("Operation outcome requires reconciliation; repeat the SAME Idempotency-Key or reconcile operation " + operationId, cause);
        this.operationId = operationId;
    }
    public UUID getOperationId() { return operationId; }
}
