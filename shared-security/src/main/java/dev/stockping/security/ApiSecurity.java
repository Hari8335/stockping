package dev.stockping.security;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
@Import(TokenConfiguration.class)
public class ApiSecurity {
    @Bean
    RequestCorrelationFilter correlationFilter() { return new RequestCorrelationFilter(); }
    @Bean
    SecurityFilterChain apiSecurity(HttpSecurity http, @Value("${auth.login-enabled:false}") boolean loginEnabled) throws Exception {
        var converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(jwt -> List.of(new SimpleGrantedAuthority("ROLE_" + jwt.getClaimAsString("role"))));
        http.csrf(csrf -> csrf.disable()) // Only explicit Authorization bearer headers; no ambient auth cookies.
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .requestCache(cache -> cache.disable())
                .logout(logout -> logout.disable())
                .authorizeHttpRequests(auth -> {
                    auth.dispatcherTypeMatchers(DispatcherType.ERROR).permitAll();
                    auth.requestMatchers(HttpMethod.GET, "/actuator/health").permitAll();
                    if (loginEnabled) auth.requestMatchers(HttpMethod.POST, "/api/auth/login").permitAll();
                    auth.anyRequest().hasRole("MERCHANT_ADMIN");
                })
                .exceptionHandling(errors -> errors
                        .authenticationEntryPoint((req, res, ex) -> error(res, 401, "UNAUTHENTICATED", "Authentication required"))
                        .accessDeniedHandler((req, res, ex) -> error(res, 403, "FORBIDDEN", "Merchant administrator role required")))
                .oauth2ResourceServer(oauth -> oauth.jwt(jwt -> jwt.jwtAuthenticationConverter(converter))
                        .authenticationEntryPoint((req, res, ex) -> error(res, 401, "INVALID_TOKEN", "Token is invalid or expired"))
                        .accessDeniedHandler((req, res, ex) -> error(res, 403, "FORBIDDEN", "Merchant administrator role required")));
        return http.build();
    }

    private static void error(HttpServletResponse response, int status, String code, String detail) throws IOException {
        response.setStatus(status);
        response.setContentType("application/problem+json");
        if (status == 401) response.setHeader("WWW-Authenticate", "Bearer");
        response.getWriter().write("{\"type\":\"about:blank\",\"status\":" + status
                + ",\"code\":\"" + code + "\",\"detail\":\"" + detail + "\"}");
    }
}
