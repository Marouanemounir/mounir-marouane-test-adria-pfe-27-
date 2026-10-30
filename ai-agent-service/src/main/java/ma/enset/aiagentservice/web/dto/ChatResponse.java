package ma.enset.aiagentservice.web.dto;

import java.util.List;

public record ChatResponse(String answer, List<String> toolsUsed) { }
