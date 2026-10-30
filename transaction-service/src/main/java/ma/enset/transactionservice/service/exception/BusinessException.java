package ma.enset.transactionservice.service.exception;
public class BusinessException extends RuntimeException {
    private final int status;
    public BusinessException(int status, String message) { super(message); this.status = status; }
    public int getStatus() { return status; }
}
