package ma.enset.aiagentservice.config;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import org.springframework.ai.mcp.SyncMcpToolCallbackProvider;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

import java.net.http.HttpRequest;
import java.time.Duration;

@Configuration(proxyBeanMethods = false)
@Profile("llm")
public class McpClientConfiguration {
    @Bean(destroyMethod = "closeGracefully")
    McpSyncClient accountMcpClient(@Value("${bank.mcp.account-url}") String url,
            @Value("${bank.mcp.auth-token}") String token) {
        return connect(url, token);
    }

    @Bean(destroyMethod = "closeGracefully")
    McpSyncClient transactionMcpClient(@Value("${bank.mcp.transaction-url}") String url,
            @Value("${bank.mcp.auth-token}") String token) {
        return connect(url, token);
    }

    @Bean
    ToolCallbackProvider bankingMcpTools(@Qualifier("accountMcpClient") McpSyncClient accounts,
            @Qualifier("transactionMcpClient") McpSyncClient transactions) {
        return new SyncMcpToolCallbackProvider(accounts, transactions);
    }

    private McpSyncClient connect(String url, String token) {
        if (token.isBlank()) {
            throw new IllegalStateException("MCP_AUTH_TOKEN must be configured before enabling the llm profile");
        }
        var transport = HttpClientStreamableHttpTransport.builder(url)
                .endpoint("/mcp")
                .requestBuilder(HttpRequest.newBuilder().header("Authorization", "Bearer " + token))
                .build();
        var client = McpClient.sync(transport).requestTimeout(Duration.ofSeconds(20)).build();
        try {
            client.initialize();
            return client;
        } catch (RuntimeException exception) {
            client.closeGracefully();
            throw exception;
        }
    }
}
