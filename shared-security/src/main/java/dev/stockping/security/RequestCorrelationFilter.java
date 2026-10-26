package dev.stockping.security;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.util.UUID;
import org.slf4j.*;
import org.springframework.web.filter.OncePerRequestFilter;

/** Server-generated IDs avoid recording arbitrary client input, paths, or personal data. */
@org.springframework.core.annotation.Order(org.springframework.core.Ordered.HIGHEST_PRECEDENCE)
public class RequestCorrelationFilter extends OncePerRequestFilter {
    private static final Logger LOG = LoggerFactory.getLogger(RequestCorrelationFilter.class);
    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String id = UUID.randomUUID().toString();
        response.setHeader("X-Request-ID", id);
        try (var ignored = MDC.putCloseable("request.id", id)) {
            try { chain.doFilter(request, response); }
            finally { LOG.info("HTTP request completed method={} status={}", request.getMethod(), response.getStatus()); }
        }
    }
}
