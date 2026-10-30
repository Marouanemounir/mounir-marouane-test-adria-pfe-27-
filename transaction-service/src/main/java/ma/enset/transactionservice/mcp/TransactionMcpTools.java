package ma.enset.transactionservice.mcp;

import ma.enset.transactionservice.service.TransactionService;
import ma.enset.transactionservice.web.rest.dto.TransactionResponse;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

@Component
public class TransactionMcpTools {
    private final TransactionService transactions;

    public TransactionMcpTools(TransactionService transactions) {
        this.transactions = transactions;
    }

    @Tool(description = "Read a recorded transaction by transaction UUID")
    public TransactionResponse getTransactionDetails(
            @ToolParam(description = "Transaction UUID") String transactionId) {
        return transactions.getTransaction(UUID.fromString(transactionId));
    }

    @Tool(description = "Read the recorded transaction history for an account UUID")
    public List<TransactionResponse> getTransactionsByAccount(
            @ToolParam(description = "Account UUID") String accountId) {
        return transactions.getHistory(UUID.fromString(accountId));
    }
}
