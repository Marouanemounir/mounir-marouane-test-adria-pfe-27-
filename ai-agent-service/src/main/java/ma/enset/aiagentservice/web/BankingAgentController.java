package ma.enset.aiagentservice.web;

import jakarta.validation.Valid;
import ma.enset.aiagentservice.service.BankingAgentService;
import ma.enset.aiagentservice.web.dto.ChatRequest;
import ma.enset.aiagentservice.web.dto.ChatResponse;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class BankingAgentController {
    private final BankingAgentService agent;

    public BankingAgentController(BankingAgentService agent) { this.agent = agent; }

    @PostMapping("/api/chat")
    public ChatResponse chat(@Valid @RequestBody ChatRequest request) {
        return agent.chat(request.message());
    }
}
