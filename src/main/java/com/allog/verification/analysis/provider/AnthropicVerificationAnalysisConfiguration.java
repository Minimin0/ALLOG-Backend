package com.allog.verification.analysis.provider;

import com.allog.verification.analysis.service.VerificationAnalysisProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;

/**
 * Registers the Anthropic adapter only when explicitly enabled. Disabled by default, so no provider
 * bean exists and no analysis runs. Worker activation stays a separate step.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AnthropicVerificationAnalysisProperties.class)
public class AnthropicVerificationAnalysisConfiguration {

    @Bean
    @ConditionalOnProperty(name = "allog.verification.analysis.anthropic.enabled", havingValue = "true")
    VerificationAnalysisProvider anthropicVerificationAnalysisProvider(
            AnthropicVerificationAnalysisProperties properties
    ) {
        properties.validateEnabledConfiguration();
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(properties.requestTimeout())
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(properties.requestTimeout());

        return new AnthropicVerificationAnalysisProvider(
                RestClient.builder()
                        .baseUrl("https://api.anthropic.com")
                        .requestFactory(requestFactory)
                        .build(),
                properties
        );
    }
}
