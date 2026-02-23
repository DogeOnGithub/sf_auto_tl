package com.starfield.api.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

/**
 * RestTemplate 配置类
 */
/**
 * RestTemplate 配置类
 */
@Configuration
public class RestTemplateConfig {

    /** 连接超时时间（毫秒） */
    private static final int CONNECT_TIMEOUT = 10_000;

    /** 读取超时时间（毫秒） 组装请求 payload 较大 适当放宽 */
    private static final int READ_TIMEOUT = 60_000;

    /**
     * 注册 RestTemplate Bean 配置连接和读取超时
     */
    @Bean
    public RestTemplate restTemplate() {
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(CONNECT_TIMEOUT);
        factory.setReadTimeout(READ_TIMEOUT);
        return new RestTemplate(factory);
    }
}
