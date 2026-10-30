package ma.enset.accountservice.web.rest.dto;

import java.util.UUID;

public record BalanceResponse(UUID id, double balance) {
}
