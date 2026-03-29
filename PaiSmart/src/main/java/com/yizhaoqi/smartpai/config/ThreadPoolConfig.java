package com.yizhaoqi.smartpai.config;

import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * 分级线程池配置，为 Agentic RAG 系统提供隔离的执行环境。
 *
 * <p>四个线程池各自承担不同职责，互不干扰：</p>
 * <ul>
 *   <li>charExecutor  — WebSocket 聊天主线程池，处理检索 + LLM 流式响应</li>
 *   <li>agentPool     — Agent 编排线程池，执行路由分类、查询规划等编排逻辑</li>
 *   <li>llmCallPool   — LLM 调用线程池，承载对 GLM/DeepSeek API 的 HTTP 请求</li>
 *   <li>toolPool      — Tool 执行线程池，运行知识库检索等工具调用</li>
 * </ul>
 *
 * <p>所有线程池统一使用 CallerRunsPolicy 拒绝策略，当队列和线程都满时，
 * 任务退化到调用者线程执行，实现天然的背压机制，避免任务丢失。</p>
 *
 * <p>每个线程池都有独立的定时监控任务，每 30 秒输出一次运行状态到日志，
 * 便于线上排查线程池瓶颈（队列积压、线程打满等问题）。</p>
 */
@Configuration
@EnableAsync
public class ThreadPoolConfig {

    private static final Logger logger = LoggerFactory.getLogger(ThreadPoolConfig.class);

    /**
     * WebSocket 聊天主线程池。
     *
     * <p>处理用户消息的核心链路：对话历史获取 → 混合检索 → Prompt 构建 → LLM 流式响应。
     * 主要瓶颈在 Redis（对话历史）和 Elasticsearch（混合检索），属于 IO 密集型场景，
     * 因此 max 较大（32 = CPU 核数 × 2）。</p>
     *
     * <p>参数设计：core=8, max=32, queue=200。
     * queue 容量 200 可缓冲短时间内的请求突发，配合 CallerRunsPolicy
     * 在极端情况下退化到 WebSocket I/O 线程执行，自然限制吞吐。</p>
     */
    @Bean("charExecutor")
    public Executor charExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(8);
        // IO密集型，主要与 Redis 和 ES 交互，max 设大一些：CPU核数 * 2 = 32
        executor.setMaxPoolSize(32);
        executor.setQueueCapacity(200);
        executor.setThreadNamePrefix("chat-");
        // 拒绝策略：调用者运行，队列满时退化到提交线程执行，天然背压
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        // 线程空闲时间（秒），超过后回收至 coreSize
        executor.setKeepAliveSeconds(60);
        // 优雅关闭：等待已提交的任务完成后再销毁线程池
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.initialize();
        scheduleMonitor(executor, "chatExecutor");
        return executor;
    }

    /**
     * Agent 编排线程池。
     *
     * <p>执行 Agentic RAG 的编排逻辑：查询路由分类（direct/rag/agent）、
     * 子查询规划、结果聚合等。编排任务本身是 CPU 密集型的 JSON 解析和逻辑判断，
     * 但实际耗时主要取决于下游 LLM 调用，所以 core 设较小（4），max 适中（16）。</p>
     *
     * <p>参数设计：core=4, max=16, queue=50。
     * queue 较小（50）是因为 Agent 任务不应堆积太多——如果 agentPool 队列满了，
     * 说明并发 Agent 请求过多，应通过 CallerRunsPolicy 限流。</p>
     */
    @Bean("agentPool")
    public Executor agentPool() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(4);
        executor.setMaxPoolSize(16);
        executor.setQueueCapacity(50);
        executor.setThreadNamePrefix("agent-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.setKeepAliveSeconds(60);
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.initialize();
        scheduleMonitor(executor, "agentPool");
        return executor;
    }

    /**
     * LLM 调用线程池。
     *
     * <p>承载所有对大模型 API（GLM-5 / DeepSeek）的 HTTP 调用。
     * 这些调用是典型的 IO 密集型任务——大部分时间在等待网络响应（通常 1~10s），
     * 因此需要较大的并发容量。</p>
     *
     * <p>参数设计：core=8, max=32, queue=200。
     * 与 charExecutor 相同的参数，因为两者瓶颈相同（都是等待外部 IO）。
     * LLM 调用耗时较长，队列需要足够大以容纳等待中的请求。</p>
     */
    @Bean("llmCallPool")
    public Executor llmCallPool() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(8);
        executor.setMaxPoolSize(32);
        executor.setQueueCapacity(200);
        executor.setThreadNamePrefix("llm-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.setKeepAliveSeconds(60);
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.initialize();
        scheduleMonitor(executor, "llmCallPool");
        return executor;
    }

    /**
     * Tool 执行线程池。
     *
     * <p>运行 Agent 的工具调用：知识库检索（HybridSearchService）、
     * 查询改写、结果评估等。工具调用涉及 ES 检索（IO 密集）和少量计算（评分排序），
     * 整体偏 IO 密集，但并发量受 Agent 信号量（max=20）限制。</p>
     *
     * <p>参数设计：core=8, max=16, queue=100。
     * max 较小（16）是因为 Tool 调用的并发上限由信号量控制，不需要太大。
     * queue=100 足以缓冲信号量放行后的任务排队。</p>
     */
    @Bean("toolPool")
    public Executor toolPool() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(8);
        executor.setMaxPoolSize(16);
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("tool-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.setKeepAliveSeconds(60);
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.initialize();
        scheduleMonitor(executor, "toolPool");
        return executor;
    }

    /**
     * 为指定线程池注册定时监控任务。
     *
     * <p>每 30 秒输出一次线程池状态到日志，格式示例：</p>
     * <pre>
     * [ThreadPoolMonitor] chatExecutor - active:3, pool:8/32, queue:15/200, completed:1024, waiting:12
     * </pre>
     * <p>各字段含义：</p>
     * <ul>
     *   <li>active    — 当前正在执行任务的线程数</li>
     *   <li>pool      — 当前线程数 / 最大线程数</li>
     *   <li>queue     — 队列中等待的任务数 / 队列总容量</li>
     *   <li>completed — 历史已完成任务总数</li>
     *   <li>waiting   — 尚未开始执行的任务数（队列中 + 等待线程）</li>
     * </ul>
     *
     * <p>监控线程设为 daemon 线程，不会阻止 JVM 退出。</p>
     *
     * @param executor 要监控的线程池
     * @param name     线程池名称，用于日志标识
     */
    private void scheduleMonitor(ThreadPoolTaskExecutor executor, String name) {
        ScheduledExecutorService monitor = Executors.newSingleThreadScheduledExecutor(
            r -> { Thread t = new Thread(r, name + "-monitor"); t.setDaemon(true); return t; });
        monitor.scheduleAtFixedRate(() -> {
            ThreadPoolExecutor pool = executor.getThreadPoolExecutor();
            logger.info("[ThreadPoolMonitor] {} - active:{}, pool:{}/{}, queue:{}/{}, completed:{}, waiting:{}",
                name,
                pool.getActiveCount(),
                pool.getPoolSize(),
                pool.getMaximumPoolSize(),
                pool.getQueue().size(),
                pool.getQueue().remainingCapacity() + pool.getQueue().size(),
                pool.getCompletedTaskCount(),
                pool.getTaskCount() - pool.getCompletedTaskCount() - pool.getActiveCount()
            );
        }, 30, 30, TimeUnit.SECONDS);
    }
}
