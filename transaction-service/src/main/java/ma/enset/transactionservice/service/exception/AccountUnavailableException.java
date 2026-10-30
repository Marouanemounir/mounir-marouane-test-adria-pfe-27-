package ma.enset.transactionservice.service.exception;
public class AccountUnavailableException extends RuntimeException {
    public AccountUnavailableException(String message, Throwable cause) { super(message, cause); }
    public AccountUnavailableException(String message) { super(message); }
}
