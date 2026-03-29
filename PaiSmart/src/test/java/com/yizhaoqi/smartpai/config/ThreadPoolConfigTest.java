package com.yizhaoqi.smartpai.config;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 纯单元测试：直接实例化 ThreadPoolConfig，不需要 Spring 上下文。
 */
class ThreadPoolConfigTest {

    private final ThreadPoolConfig config = new ThreadPoolConfig();

    @Test
    @DisplayName("charExecutor 线程池参数正确")
    void charExecutor_shouldHaveCorrectParams() {
        Executor executor = config.charExecutor();
        assertPoolParams((ThreadPoolTaskExecutor) executor, "chat-", 8, 32);
    }

    @Test
    @DisplayName("agentPool 线程池参数正确")
    void agentPool_shouldHaveCorrectParams() {
        Executor executor = config.agentPool();
        assertPoolParams((ThreadPoolTaskExecutor) executor, "agent-", 4, 16);
    }

    @Test
    @DisplayName("llmCallPool 线程池参数正确")
    void llmCallPool_shouldHaveCorrectParams() {
        Executor executor = config.llmCallPool();
        assertPoolParams((ThreadPoolTaskExecutor) executor, "llm-", 8, 32);
    }

    @Test
    @DisplayName("toolPool 线程池参数正确")
    void toolPool_shouldHaveCorrectParams() {
        Executor executor = config.toolPool();
        assertPoolParams((ThreadPoolTaskExecutor) executor, "tool-", 8, 16);
    }

    @Test
    @DisplayName("四个线程池都已初始化且可提交任务")
    void allPools_shouldAcceptTasks() throws InterruptedException {
        ThreadPoolTaskExecutor chat = (ThreadPoolTaskExecutor) config.charExecutor();
        ThreadPoolTaskExecutor agent = (ThreadPoolTaskExecutor) config.agentPool();
        ThreadPoolTaskExecutor llm = (ThreadPoolTaskExecutor) config.llmCallPool();
        ThreadPoolTaskExecutor tool = (ThreadPoolTaskExecutor) config.toolPool();

        chat.execute(() -> {});
        agent.execute(() -> {});
        llm.execute(() -> {});
        tool.execute(() -> {});

        Thread.sleep(200);
        assertThat(chat.getThreadPoolExecutor().getCompletedTaskCount()).isGreaterThanOrEqualTo(1);
        assertThat(agent.getThreadPoolExecutor().getCompletedTaskCount()).isGreaterThanOrEqualTo(1);
        assertThat(llm.getThreadPoolExecutor().getCompletedTaskCount()).isGreaterThanOrEqualTo(1);
        assertThat(tool.getThreadPoolExecutor().getCompletedTaskCount()).isGreaterThanOrEqualTo(1);
    }

    private void assertPoolParams(ThreadPoolTaskExecutor executor, String prefix, int coreSize, int maxSize) {
        ThreadPoolExecutor pool = executor.getThreadPoolExecutor();
        System.out.println("[" + executor.getThreadNamePrefix() + "] core:" + pool.getCorePoolSize()
                + ", max:" + pool.getMaximumPoolSize()
                + ", queue:" + (pool.getQueue().remainingCapacity() + pool.getQueue().size()));
        assertThat(executor.getThreadNamePrefix()).isEqualTo(prefix);
        assertThat(pool.getCorePoolSize()).isEqualTo(coreSize);
        assertThat(pool.getMaximumPoolSize()).isEqualTo(maxSize);
    }
}
