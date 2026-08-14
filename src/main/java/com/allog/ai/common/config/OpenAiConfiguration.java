package com.allog.ai.common.config;

import com.allog.ai.coaching.provider.AiCoachProvider;
import com.allog.ai.coaching.provider.OpenAiCoachProvider;
import com.allog.ai.coaching.service.AiCoachService;
import com.allog.ai.coaching.selector.CoachActionResolver;
import com.allog.ai.coaching.template.CoachTemplateFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;

import java.net.http.HttpClient;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AiProperties.class)
public class OpenAiConfiguration {

    @Bean("openAiRestClient")
    RestClient openAiRestClient(AiProperties properties) {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(properties.coach().timeout())
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(properties.coach().timeout());

        return RestClient.builder()
                .baseUrl("https://api.openai.com")
                .requestFactory(requestFactory)
                .build();
    }

    @Bean
    AiCoachProvider aiCoachProvider(
            @Qualifier("openAiRestClient") RestClient restClient,
            ObjectMapper objectMapper,
            AiProperties properties
    ) {
        return new OpenAiCoachProvider(restClient, objectMapper, properties);
    }

    @Bean
    AiCoachService aiCoachService(AiCoachProvider provider) {
        return new AiCoachService(provider, new CoachActionResolver(), new CoachTemplateFactory());
    }
}
