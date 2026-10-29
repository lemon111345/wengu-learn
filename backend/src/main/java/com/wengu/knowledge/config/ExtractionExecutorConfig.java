package com.wengu.knowledge.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * 执行知识点抽取的线程池。抽取一批要 1～3 分钟，不能占着请求线程，所以交给这个线程池在后台执行（决策 013）。
 * <p>
 * 定义了自己的 Executor 后，Spring Boot 默认不再创建 applicationTaskExecutor，
 * application.yml 中用 spring.task.execution.mode=force 让它照常创建，以后 Spring MVC 的异步请求还要用。
 */
@Configuration(proxyBeanMethods = false)
public class ExtractionExecutorConfig {

    /** 同时执行的抽取任务数。模型服务商有限流，单用户也用不着更多。 */
    private static final int THREADS = 2;

    /** 排队的任务数上限，超出时这次抽取直接失败，用户稍后重试。 */
    private static final int QUEUE_CAPACITY = 20;

    @Bean
    TaskExecutor extractionExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(THREADS);
        executor.setMaxPoolSize(THREADS);
        executor.setQueueCapacity(QUEUE_CAPACITY);
        executor.setThreadNamePrefix("extraction-");
        return executor;
    }
}
