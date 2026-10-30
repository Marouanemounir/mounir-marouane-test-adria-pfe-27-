package ma.enset.accountservice.web.graphql;

import ma.enset.accountservice.service.AccountService;
import ma.enset.accountservice.web.rest.dto.AccountResponse;
import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.stereotype.Controller;

import java.util.List;
import java.util.UUID;

@Controller
public class AccountGraphQLController {
    private final AccountService accounts;

    public AccountGraphQLController(AccountService accounts) {
        this.accounts = accounts;
    }

    @QueryMapping
    public List<AccountResponse> accounts() {
        return accounts.getAllAccounts().stream().map(AccountResponse::from).toList();
    }

    @QueryMapping
    public AccountResponse accountById(@Argument UUID id) {
        return AccountResponse.from(accounts.getAccountById(id));
    }
}
