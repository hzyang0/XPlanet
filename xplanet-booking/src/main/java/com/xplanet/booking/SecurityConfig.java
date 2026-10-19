package com.xplanet.booking;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.util.List;
import org.springframework.context.annotation.*;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.filter.OncePerRequestFilter;

@Configuration
public class SecurityConfig {
  @Bean
  SecurityFilterChain security(HttpSecurity http, AuthService auth) throws Exception {
    return http.csrf(c -> c.disable())
        .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .authorizeHttpRequests(
            a ->
                a.requestMatchers(
                        "/",
                        "/index.html",
                        "/app.js",
                        "/app.css",
                        "/favicon.ico",
                        "/actuator/health",
                        "/api/auth/login",
                        "/api/auth/register")
                    .permitAll()
                    .requestMatchers("/api/**")
                    .authenticated()
                    .anyRequest()
                    .denyAll())
        .exceptionHandling(
            e ->
                e.authenticationEntryPoint((req, res, ex) -> error(res, 401, "请先登录"))
                    .accessDeniedHandler((req, res, ex) -> error(res, 403, "无权执行该操作")))
        .headers(
            h ->
                h.contentSecurityPolicy(
                    p ->
                        p.policyDirectives(
                            "default-src 'self'; frame-ancestors 'none'; base-uri 'self';"
                                + " form-action 'self'")))
        .addFilterBefore(
            new OncePerRequestFilter() {
              @Override
              protected void doFilterInternal(
                  HttpServletRequest req, HttpServletResponse res, FilterChain chain)
                  throws ServletException, IOException {
                String header = req.getHeader("Authorization");
                if (header != null && header.startsWith("Bearer "))
                  auth.resolve(header.substring(7))
                      .ifPresent(
                          actor ->
                              SecurityContextHolder.getContext()
                                  .setAuthentication(
                                      new UsernamePasswordAuthenticationToken(
                                          actor,
                                          null,
                                          List.of(
                                              new SimpleGrantedAuthority(
                                                  "ROLE_" + actor.role())))));
                chain.doFilter(req, res);
              }
            },
            UsernamePasswordAuthenticationFilter.class)
        .build();
  }

  private static void error(HttpServletResponse res, int status, String message)
      throws IOException {
    res.setStatus(status);
    res.setContentType("application/json;charset=UTF-8");
    res.getWriter().write("{\"status\":" + status + ",\"message\":\"" + message + "\"}");
  }
}
