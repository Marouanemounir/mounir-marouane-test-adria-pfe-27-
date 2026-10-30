package ma.enset.aiagentservice.service;

import ma.enset.aiagentservice.web.dto.ChatResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

@Service
public class BankingAgentService {
    private static final Logger log = LoggerFactory.getLogger(BankingAgentService.class);
    private final ObjectProvider<ChatClient> chatClients;
    private final ObjectProvider<ToolCallbackProvider> mcpTools;

    public BankingAgentService(ObjectProvider<ChatClient> chatClients, ObjectProvider<ToolCallbackProvider> mcpTools) {
        this.chatClients = chatClients;
        this.mcpTools = mcpTools;
    }

    public ChatResponse chat(String message) {
        ChatClient client = chatClients.getIfAvailable();
        if (client == null) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Agent en attente de configuration LLM : definir OPENAI_API_KEY et OPENAI_MODEL puis activer le profil llm");
        }
        List<String> calls = Collections.synchronizedList(new ArrayList<>());
        try {
            ToolCallback[] callbacks = Arrays.stream(mcpTools.getObject().getToolCallbacks())
                    .map(tool -> tracked(tool, calls)).toArray(ToolCallback[]::new);
            String answer = client.prompt().user(message).toolCallbacks(callbacks).call().content();
            if (answer == null || answer.isBlank()) {
                throw new IllegalStateException("Empty LLM response");
            }
            return new ChatResponse(answer, List.copyOf(calls));
        } catch (RuntimeException exception) {
            log.warn("Banking agent consultation failed ({})", exception.getClass().getSimpleName());
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "Consultation indisponible : verifier le fournisseur LLM et les serveurs MCP");
        }
    }

    private ToolCallback tracked(ToolCallback delegate, List<String> calls) {
        return new ToolCallback() {
            @Override
            public ToolDefinition getToolDefinition() { return delegate.getToolDefinition(); }

            @Override
            public ToolMetadata getToolMetadata() { return delegate.getToolMetadata(); }

            @Override
            public String call(String input) { return call(input, null); }

            @Override
            public String call(String input, ToolContext context) {
                String result = delegate.call(input, context);
                String name = getToolDefinition().name();
                calls.add(name);
                log.info("MCP tool completed: {}", name);
                return result;
            }
        };
    }
}
