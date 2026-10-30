package ma.enset.transactionservice.web.graphql;

import ma.enset.transactionservice.service.TransactionService;
import ma.enset.transactionservice.web.rest.dto.TransactionResponse;
import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.stereotype.Controller;

import java.util.List;
import java.util.UUID;

@Controller
public class TransactionGraphQLController {
    private final TransactionService transactions;

    public TransactionGraphQLController(TransactionService transactions) {
        this.transactions = transactions;
    }

    @QueryMapping
    public TransactionView transactionById(@Argument UUID id) {
        return TransactionView.from(transactions.getTransaction(id));
    }

    @QueryMapping
    public List<TransactionView> transactionsByAccount(@Argument UUID accountId) {
        return transactions.getHistory(accountId).stream().map(TransactionView::from).toList();
    }

    public record TransactionView(UUID id, UUID accountId, String amount, String currency,
                                  String merchantCategory, String country, String createdAt) {
        static TransactionView from(TransactionResponse transaction) {
            return new TransactionView(transaction.id(), transaction.accountId(), transaction.amount().toPlainString(),
                    transaction.currency(), transaction.merchantCategory(), transaction.country(),
                    transaction.createdAt().toString());
        }
    }
}
