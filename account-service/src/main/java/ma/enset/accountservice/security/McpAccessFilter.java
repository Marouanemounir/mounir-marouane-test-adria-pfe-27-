package ma.enset.accountservice.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** Minimal service-token boundary; an unset token denies all protected requests. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class McpAccessFilter extends OncePerRequestFilter {
    private final String token;

    public McpAccessFilter(@Value("${bank.mcp.auth-token:}") String token) {
        this.token = token;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getServletPath();
        return !(path.equals("/mcp") || path.startsWith("/mcp/"));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (token.isBlank()) {
            response.setStatus(503);
            response.setContentType("application/json");
            response.getWriter().write("{\"error\":\"Service authentication token is not configured\"}");
            return;
        }
        String authorization = request.getHeader("Authorization");
        if (authorization == null || !MessageDigest.isEqual(
                ("Bearer " + token).getBytes(StandardCharsets.UTF_8),
                authorization.getBytes(StandardCharsets.UTF_8))) {
            response.setStatus(401);
            response.setHeader("WWW-Authenticate", "Bearer");
            return;
        }
        chain.doFilter(request, response);
    }
}
