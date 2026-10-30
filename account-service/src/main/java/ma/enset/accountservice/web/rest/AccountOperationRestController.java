package ma.enset.accountservice.web.rest;

import jakarta.validation.Valid;
import ma.enset.accountservice.service.AccountOperationService;
import ma.enset.accountservice.web.rest.dto.*;
import org.springframework.web.bind.annotation.*;
import java.util.UUID;

@RestController
@RequestMapping("/api/accounts")
public class AccountOperationRestController {
    private final AccountOperationService service;
    public AccountOperationRestController(AccountOperationService service) { this.service = service; }

    @PutMapping("/operations/{id}")
    public AccountOperationResponse execute(@PathVariable UUID id, @Valid @RequestBody AccountOperationRequest request) {
        return service.execute(id, request);
    }

    @GetMapping("/operations/{id}")
    public AccountOperationResponse get(@PathVariable UUID id) { return service.get(id); }

    @PostMapping("/transfers")
    public AccountOperationResponse transfer(@RequestHeader("Idempotency-Key") UUID id,
            @Valid @RequestBody AccountOperationRequest request) {
        if (!"TRANSFER".equals(request.type())) throw new IllegalArgumentException("TRANSFER type required");
        return service.execute(id, request);
    }
}
