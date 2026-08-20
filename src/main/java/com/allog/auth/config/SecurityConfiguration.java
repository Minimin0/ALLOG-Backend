package com.allog.auth.config;

import com.allog.auth.security.AccessTokenService;
import com.allog.auth.security.LocalBearerAuthenticationFilter;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import org.springframework.security.web.context.NullSecurityContextRepository;
import org.springframework.security.web.savedrequest.NullRequestCache;

@Configuration(proxyBeanMethods = false)
public class SecurityConfiguration {

    @Bean
    SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            AccessTokenService tokenService
    ) throws Exception {
        LocalBearerAuthenticationFilter authenticationFilter = new LocalBearerAuthenticationFilter(tokenService);
        http
                .csrf(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .securityContext(context -> context
                        .securityContextRepository(new NullSecurityContextRepository()))
                .requestCache(cache -> cache.requestCache(new NullRequestCache()))
                .exceptionHandling(exceptions -> exceptions.authenticationEntryPoint(
                        (request, response, exception) -> response.setStatus(HttpServletResponse.SC_UNAUTHORIZED)
                ))
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers(HttpMethod.POST, "/api/v1/auth/login", "/api/v1/auth/signup").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/v1/dev/ai-coach/preview").permitAll()
                        .requestMatchers(HttpMethod.PUT, "/api/v1/verification-media/uploads/*").permitAll()
                        .anyRequest().authenticated())
                .addFilterBefore(authenticationFilter, AnonymousAuthenticationFilter.class);
        return http.build();
    }
}
