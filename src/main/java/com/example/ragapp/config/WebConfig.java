package com.example.ragapp.config;

import java.util.List;

import com.example.ragapp.common.CallerArgumentResolver;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.AsyncSupportConfigurer;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Settings for streamed answers (Server-Sent Events).
 * Without this, Spring uses a simple default executor that logs a warning
 * and creates a new thread for every request.
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    private static final long STREAM_TIMEOUT_MS = 180_000;   // an answer must finish within 3 minutes

    private final CallerArgumentResolver callerArgumentResolver;
    private final CorsProperties corsProperties;

    public WebConfig(CallerArgumentResolver callerArgumentResolver, CorsProperties corsProperties) {
        this.callerArgumentResolver = callerArgumentResolver;
        this.corsProperties = corsProperties;
    }

    /**
     * CORS: by default a web page from another website cannot call the API from a browser.
     * Add the website to app.cors.allowed-origins to allow it. Nothing else is opened up.
     */
    @Override
    public void addCorsMappings(CorsRegistry registry) {
        if (corsProperties.allowedOrigins() == null || corsProperties.allowedOrigins().isEmpty()) {
            return;
        }
        registry.addMapping("/api/**")
                .allowedOrigins(corsProperties.allowedOrigins().toArray(String[]::new))
                .allowedMethods("GET", "POST", "PUT", "DELETE")
                .allowedHeaders("Content-Type", "X-Tenant-Id", "X-User-Id")
                .exposedHeaders("X-Request-Id", "Retry-After")
                .maxAge(3600);
    }

    /** Makes "Caller caller" work as a controller method parameter. */
    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(callerArgumentResolver);
    }

    @Override
    public void configureAsyncSupport(AsyncSupportConfigurer configurer) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(4);
        executor.setMaxPoolSize(16);
        executor.setQueueCapacity(50);
        executor.setThreadNamePrefix("stream-");
        executor.initialize();

        configurer.setTaskExecutor(executor);
        configurer.setDefaultTimeout(STREAM_TIMEOUT_MS);
    }
}
