package com.yizhaoqi.smartpai.config;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@Configuration
@EnableAsync
public class ThreadPoolConfig {
    @Bean("charExecutor")
    public Executor charExecutor() {
            ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
            executor.setCorePoolSize(8);
            //IO密集型多点 主要是redis和ES交互 所以max多点---16*2 =32
            executor.setMaxPoolSize(32);
            executor.setQueueCapacity(200);
            executor.setThreadNamePrefix("chat-");
            // 拒绝策略：调用者运行
            executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
            // 线程空闲时间（秒）
            executor.setKeepAliveSeconds(60);
            // 等待任务完成后才关闭线程池 不需要手动关闭 非常优雅
            executor.setWaitForTasksToCompleteOnShutdown(true);
            // 初始化线程池
            executor.initialize();
            return executor;
    }
}
