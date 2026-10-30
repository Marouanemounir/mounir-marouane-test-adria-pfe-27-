package ma.enset.transactionservice.web.rest;
import jakarta.validation.ConstraintViolationException;
import ma.enset.transactionservice.service.exception.*;
import org.springframework.dao.*;
import org.springframework.http.*;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {
    @ExceptionHandler(BusinessException.class)
    public ProblemDetail business(BusinessException e) { return ProblemDetail.forStatusAndDetail(HttpStatusCode.valueOf(e.getStatus()), e.getMessage()); }
    @ExceptionHandler(RecoveryRequiredException.class)
    public ProblemDetail recovery(RecoveryRequiredException e) {
        ProblemDetail p = ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE, e.getMessage());
        p.setProperty("operationId", e.getOperationId());
        p.setProperty("recovery", "/api/transactions/operations/" + e.getOperationId() + "/reconcile");
        return p;
    }
    @ExceptionHandler(AccountUnavailableException.class)
    public ProblemDetail unavailable(AccountUnavailableException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE, e.getMessage());
    }
    @ExceptionHandler({DataIntegrityViolationException.class, ConcurrencyFailureException.class})
    public ProblemDetail conflict(DataAccessException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, "Concurrent operation; retry with the SAME Idempotency-Key");
    }
    @ExceptionHandler({DataAccessException.class, jakarta.persistence.PersistenceException.class,
            org.springframework.transaction.TransactionException.class})
    public ProblemDetail persistence(Exception e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE,
                "Local persistence unavailable; retry with the SAME Idempotency-Key");
    }
    @ExceptionHandler({ConstraintViolationException.class, IllegalArgumentException.class})
    public ProblemDetail invalid(RuntimeException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
    }
    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException e,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        ProblemDetail p = ProblemDetail.forStatusAndDetail(status, "Request validation failed");
        p.setProperty("errors", e.getBindingResult().getFieldErrors().stream()
                .map(error -> error.getField() + ": " + error.getDefaultMessage()).toList());
        return handleExceptionInternal(e, p, headers, status, request);
    }
}
