package ma.enset.transactionservice.web.rest;
import jakarta.validation.Valid;
import ma.enset.transactionservice.service.TransactionService;
import ma.enset.transactionservice.web.rest.dto.*;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.net.URI;
import java.util.*;

@RestController
@RequestMapping("/api/transactions")
public class TransactionRestController {
    private final TransactionService service;
    public TransactionRestController(TransactionService service) { this.service = service; }
    @PostMapping
    public ResponseEntity<TransactionResponse> create(@Valid @RequestBody CreateTransactionRequest request,
            @RequestHeader("Idempotency-Key") UUID operationId) {
        TransactionResponse response = service.createTransaction(request, operationId);
        return ResponseEntity.created(URI.create("/api/transactions/" + response.id()))
                .header("Idempotency-Key", operationId.toString()).body(response);
    }
    @GetMapping public List<TransactionResponse> all() { return service.getTransactions(); }
    @GetMapping("/{id}") public TransactionResponse get(@PathVariable UUID id) { return service.getTransaction(id); }
    @GetMapping("/account/{accountId}") public List<TransactionResponse> history(@PathVariable UUID accountId) {
        return service.getHistory(accountId);
    }
    @PostMapping("/transfers")
    public ResponseEntity<TransferResponse> transfer(@Valid @RequestBody TransferRequest request,
            @RequestHeader("Idempotency-Key") UUID operationId) {
        return ResponseEntity.status(201).header("Idempotency-Key", operationId.toString())
                .body(service.transfer(request, operationId));
    }
    @GetMapping("/operations/{id}") public OperationResponse operation(@PathVariable UUID id) { return service.operation(id); }
    @PostMapping("/operations/{id}/reconcile") public OperationResponse reconcile(@PathVariable UUID id) {
        return service.reconcile(id);
    }
}
