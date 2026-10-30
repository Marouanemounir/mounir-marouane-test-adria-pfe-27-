package ma.enset.aiagentservice;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import ma.enset.aiagentservice.config.McpClientConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;

import java.util.Arrays;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Opt-in real HTTP test against the services seeded by scripts/test-step6.ps1. */
@EnabledIfEnvironmentVariable(named = "STEP6_ACCOUNT_ID", matches = ".+")
class McpClientConfigurationIT {
    @Test
    void actualAgentClientsDiscoverBothServersAndReadBalance() throws Exception {
        try (var context = new AnnotationConfigApplicationContext()) {
            context.getEnvironment().setActiveProfiles("llm");
            context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("smoke", Map.of(
                    "bank.mcp.account-url", System.getenv().getOrDefault("ACCOUNT_MCP_URL", "http://localhost:8081"),
                    "bank.mcp.transaction-url", System.getenv().getOrDefault("TRANSACTION_MCP_URL", "http://localhost:8082"),
                    "bank.mcp.auth-token", System.getenv("MCP_AUTH_TOKEN"))));
            context.register(McpClientConfiguration.class);
            context.refresh();
            var accountClient = context.getBean("accountMcpClient", McpSyncClient.class);
            var transactionClient = context.getBean("transactionMcpClient", McpSyncClient.class);
            assertThat(accountClient.listTools().tools()).hasSize(3);
            assertThat(transactionClient.listTools().tools()).hasSize(2);
            var callbacks = context.getBean(ToolCallbackProvider.class).getToolCallbacks();
            assertThat(callbacks).hasSize(5);
            var balanceTool = Arrays.stream(callbacks)
                    .filter(tool -> tool.getToolDefinition().name().endsWith("getAccountBalance"))
                    .findFirst().orElseThrow();
            String input = new ObjectMapper().writeValueAsString(Map.of("accountId", System.getenv("STEP6_ACCOUNT_ID")));
            var mapper = new ObjectMapper();
            var content = mapper.readTree(balanceTool.call(input));
            var balance = mapper.readTree(content.get(0).get("text").asText());
            assertThat(balance.get("balance").decimalValue()).isEqualByComparingTo("4749.75");
            assertThat(balance.get("currency").asText()).isEqualTo("EUR");
            var history = transactionClient.callTool(new McpSchema.CallToolRequest("getTransactionsByAccount",
                    Map.of("accountId", System.getenv("STEP6_ACCOUNT_ID"))));
            assertThat(history.isError()).isNotEqualTo(true);
            assertThat(history.content()).isNotEmpty();
        }
    }
}
