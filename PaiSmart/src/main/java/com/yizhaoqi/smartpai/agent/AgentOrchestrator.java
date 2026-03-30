package com.yizhaoqi.smartpai.agent;

import com.yizhaoqi.smartpai.agent.tool.KnowledgeSearchTool;
import com.yizhaoqi.smartpai.entity.SearchResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

@Service
public class AgentOrchestrator {

    private static final Logger logger = LoggerFactory.getLogger(AgentOrchestrator.class);

    private final Semaphore agentSemaphore;
    private final Executor agentPool;
    private final Executor toolPool;
    private final PlannerAgent plannerAgent;
    private final KnowledgeSearchTool knowledgeSearchTool;
    private final long totalTimeoutMs;
    private final long acquireTimeoutMs;

    public AgentOrchestrator(
            @Qualifier("agentPool") Executor agentPool,
            @Qualifier("toolPool") Executor toolPool,
            PlannerAgent plannerAgent,
            KnowledgeSearchTool knowledgeSearchTool,
            @Value("${agent.semaphore.max-concurrent:20}") int maxConcurrent,
            @Value("${agent.semaphore.acquire-timeout:3000}") long acquireTimeoutMs,
            @Value("${agent.timeout.total:15000}") long totalTimeoutMs) {
        // 20个并发
        this.agentSemaphore = new Semaphore(maxConcurrent);
        this.agentPool = agentPool;
        this.toolPool = toolPool;
        this.plannerAgent = plannerAgent;
        this.knowledgeSearchTool = knowledgeSearchTool;
        this.acquireTimeoutMs = acquireTimeoutMs;
        this.totalTimeoutMs = totalTimeoutMs;
    }

    /**
     * 异步执行 Agent，带信号量限流。
     * 获取不到信号量时返回 null，由 ChatHandler 降级到 rag 路径。
     */
    public CompletableFuture<AgentResult> executeAsync(
            String userId, String message, List<Map<String, String>> history) {
        return CompletableFuture.supplyAsync(() -> {
            boolean acquired = false;
            try {
                acquired = agentSemaphore.tryAcquire(acquireTimeoutMs, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            }

            if (!acquired) {
                logger.warn("Agent并发已满, 降级到rag路径, userId={}", userId);
                return null;
            }

            try {
                return execute(userId, message, history);
            } finally {
                agentSemaphore.release();
            }
        }, agentPool);
    }

    /**
     * Agent 编排主逻辑（同步，在 agentPool 线程中执行）。
     */
    private AgentResult execute(String userId, String message, List<Map<String, String>> history) {
        long startTime = System.currentTimeMillis();

        // 设置 AgentContext
        AgentContext.setUserId(userId);

        try {
            // Step 1: 规划子查询
            List<String> subQueries = plannerAgent.plan(message, history);
            logger.info("Agent规划完成: subQueries={}, 耗时={}ms",
                subQueries, System.currentTimeMillis() - startTime);

            // Step 2: 根据子查询数量动态分配每路检索配额，并行执行
            // 例如 totalBudget=10，2 个子查询则每路各取 top 5，4 个子查询则每路各取 top 2
            int totalBudget = 10;
            int perQueryK = Math.max(1, totalBudget / subQueries.size());
            List<CompletableFuture<List<SearchResult>>> searchFutures = subQueries.stream()
                .map(query -> CompletableFuture.supplyAsync(
                    () -> knowledgeSearchTool.search(query, perQueryK), toolPool))
                .collect(Collectors.toList());

            CompletableFuture.allOf(searchFutures.toArray(new CompletableFuture[0])).join();

            // Step 3: 合并所有子查询结果，去重时保留最高分，按分数降序排列，截断到总量 topK
            Map<String, SearchResult> deduped = new HashMap<>();
            for (CompletableFuture<List<SearchResult>> future : searchFutures) {
                for (SearchResult r : future.join()) {
                    String key = r.getFileMd5() + ":" + r.getChunkId();
                    SearchResult existing = deduped.get(key);
                    if (existing == null || r.getScore() > existing.getScore()) {
                        deduped.put(key, r);
                    }
                }
            }
            List<SearchResult> allResults = new ArrayList<>(deduped.values());
            allResults.sort((a, b) -> Double.compare(b.getScore(), a.getScore()));

            // 去重后可能不足 totalBudget，截断只做上限保护
            if (allResults.size() > totalBudget) {
                allResults = allResults.subList(0, totalBudget);
            }

            long totalLatency = System.currentTimeMillis() - startTime;
            logger.info("Agent执行完成: totalLatency={}ms, subQueries={}, totalResults={}",
                totalLatency, subQueries.size(), allResults.size());

            return new AgentResult(allResults);

        } finally {
            AgentContext.clear();
        }
    }
}
