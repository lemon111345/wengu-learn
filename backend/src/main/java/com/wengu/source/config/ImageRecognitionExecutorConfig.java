package com.wengu.source.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * 识别截图的线程池（决策 044）。每个排队的任务都在内存中带着最多 10 MB 的图片，所以队列要小。
 */
@Configuration(proxyBeanMethods = false)
public class ImageRecognitionExecutorConfig {

    private static final int THREADS = 2;

    /** 排队的任务数上限，超出时提示用户稍后再试。 */
    private static final int QUEUE_CAPACITY = 4;

    @Bean
    TaskExecutor imageRecognitionExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(THREADS);
        executor.setMaxPoolSize(THREADS);
        executor.setQueueCapacity(QUEUE_CAPACITY);
        executor.setThreadNamePrefix("image-recognition-");
        return executor;
    }
}
