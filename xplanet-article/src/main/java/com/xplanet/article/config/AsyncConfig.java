package com.xplanet.article.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.ThreadPoolExecutor;

@Configuration
@EnableAsync
public class AsyncConfig {

    /**
     * 缓存延迟任务专用线程池。
     * 不和 RocketMQ 消费线程混用,避免相互阻塞。
     */
    @Bean("cacheTaskExecutor")
    public ThreadPoolTaskExecutor cacheTaskExecutor() {
        ThreadPoolTaskExecutor exec = new ThreadPoolTaskExecutor();
        exec.setCorePoolSize(4);
        exec.setMaxPoolSize(16);
        exec.setQueueCapacity(1000);
        exec.setThreadNamePrefix("cache-task-");
        // 延迟双删丢失不致命,用 DiscardOldest 避免阻塞业务
        exec.setRejectedExecutionHandler(new ThreadPoolExecutor.DiscardOldestPolicy());
        exec.initialize();
        return exec;
    }
}
