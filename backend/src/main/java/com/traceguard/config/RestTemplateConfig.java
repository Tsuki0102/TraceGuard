package com.traceguard.config;

import com.traceguard.integration.IntegrationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

/**
 * GAP-009：提供 RestTemplate Bean，供 JiraClient / ZentaoClient 注入。
 * 超时时间取自 traceguard.integration.timeout-seconds（默认 10s）。
 */
@Configuration
public class RestTemplateConfig {

    @Bean
    public RestTemplate integrationRestTemplate(IntegrationProperties properties) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        int timeout = (properties.getTimeoutSeconds() != null ? properties.getTimeoutSeconds() : 10) * 1000;
        factory.setConnectTimeout(timeout);
        factory.setReadTimeout(timeout);
        return new RestTemplate(factory);
    }
}
