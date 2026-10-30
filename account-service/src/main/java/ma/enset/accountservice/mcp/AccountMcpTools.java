package ma.enset.accountservice.mcp;

import ma.enset.accountservice.service.AccountService;
import ma.enset.accountservice.web.rest.dto.AccountResponse;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

@Component
public class AccountMcpTools {
    private final AccountService accounts;

    public AccountMcpTools(AccountService accounts) {
        this.accounts = accounts;
    }

    @Tool(description = "Read account details including status, currency and current balance by account UUID")
    public AccountResponse getAccountDetails(@ToolParam(description = "Account UUID") String accountId) {
        return AccountResponse.from(accounts.getAccountById(UUID.fromString(accountId)));
    }

    @Tool(description = "Read the current balance and currency of an account by its UUID")
    public AccountBalance getAccountBalance(@ToolParam(description = "Account UUID") String accountId) {
        var account = accounts.getAccountById(UUID.fromString(accountId));
        return new AccountBalance(account.getId(), account.getBalance(), account.getCurrency());
    }

    @Tool(description = "Find accounts by an exact customer reference supplied by the user")
    public List<AccountResponse> getAccountsByCustomerReference(
            @ToolParam(description = "Exact customer reference") String reference) {
        return accounts.getAccountsByCustomerReference(reference).stream().map(AccountResponse::from).toList();
    }

    public record AccountBalance(UUID accountId, double balance, String currency) { }
}
