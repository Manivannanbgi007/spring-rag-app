package com.example.ragapp.config;

import com.example.ragapp.ingestion.IngestionProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.core.task.support.ContextPropagatingTaskDecorator;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * Background worker pool for document processing.
 *
 * "threads" documents are processed at the same time. Others wait in a queue of "queueSize".
 * When the queue is full, new uploads are rejected with a "busy" message instead of
 * overloading the server and the embedding model.
 */
@Configuration
@EnableAsync
@EnableScheduling   // lets @Scheduled jobs run (the conversation retention clean-up)
public class AsyncConfig {

    @Bean("ingestionExecutor")
    public ThreadPoolTaskExecutor ingestionExecutor(IngestionProperties properties) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(properties.threads());
        executor.setMaxPoolSize(properties.threads());
        executor.setQueueCapacity(properties.queueSize());
        executor.setThreadNamePrefix("ingest-");
        // The trace of the upload request continues in the background thread that processes the document
        executor.setTaskDecorator(new ContextPropagatingTaskDecorator());
        executor.setWaitForTasksToCompleteOnShutdown(true);   // let running documents finish on shutdown
        executor.setAwaitTerminationSeconds(30);
        return executor;
    }
}
