package ma.enset.accountservice.web.rest;

import jakarta.validation.Valid;
import ma.enset.accountservice.domain.AccountStatus;
import ma.enset.accountservice.service.AccountService;
import ma.enset.accountservice.web.rest.dto.AccountResponse;
import ma.enset.accountservice.web.rest.dto.AmountRequest;
import ma.enset.accountservice.web.rest.dto.BalanceResponse;
import ma.enset.accountservice.web.rest.dto.CreateAccountRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.net.URI;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/accounts")
public class AccountRestController {
    private final AccountService service;

    public AccountRestController(AccountService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<AccountResponse> create(@Valid @RequestBody CreateAccountRequest request) {
        AccountResponse response = AccountResponse.from(service.createAccount(request.customerReference(),
                request.status(), request.currency(), request.balance()));
        URI location = ServletUriComponentsBuilder.fromCurrentRequest().path("/{id}")
                .buildAndExpand(response.id()).toUri();
        return ResponseEntity.created(location).body(response);
    }

    @GetMapping
    public List<AccountResponse> getAll() {
        return service.getAllAccounts().stream().map(AccountResponse::from).toList();
    }

    @GetMapping("/{id}")
    public AccountResponse getById(@PathVariable UUID id) {
        return AccountResponse.from(service.getAccountById(id));
    }

    @GetMapping("/customer/{reference}")
    public List<AccountResponse> getByCustomer(@PathVariable String reference) {
        return service.getAccountsByCustomerReference(reference).stream().map(AccountResponse::from).toList();
    }

    @GetMapping("/{id}/balance")
    public BalanceResponse getBalance(@PathVariable UUID id) {
        return new BalanceResponse(id, service.getBalance(id));
    }

    @PatchMapping("/{id}/debit")
    public AccountResponse debit(@PathVariable UUID id, @Valid @RequestBody AmountRequest request) {
        return AccountResponse.from(service.debit(id, request.amount()));
    }

    @PatchMapping("/{id}/credit")
    public AccountResponse credit(@PathVariable UUID id, @Valid @RequestBody AmountRequest request) {
        return AccountResponse.from(service.credit(id, request.amount()));
    }

    @PatchMapping("/{id}/status")
    public AccountResponse updateStatus(@PathVariable UUID id, @RequestParam AccountStatus status) {
        return AccountResponse.from(service.updateStatus(id, status));
    }
}
