package com.yizhaoqi.smartpai.agent;

import com.yizhaoqi.smartpai.agent.tool.EvaluateResultsTool;
import com.yizhaoqi.smartpai.agent.tool.KnowledgeSearchTool;
import com.yizhaoqi.smartpai.agent.tool.QueryRewriteTool;
import com.yizhaoqi.smartpai.entity.SearchResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.Set;
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
    private final EvaluateResultsTool evaluateResultsTool;
    private final QueryRewriteTool queryRewriteTool;
    private final int maxRetryCount;

    public AgentOrchestrator(
            @Qualifier("agentPool") Executor agentPool,
            @Qualifier("toolPool") Executor toolPool,
            PlannerAgent plannerAgent,
            KnowledgeSearchTool knowledgeSearchTool,
            @Value("${agent.semaphore.max-concurrent:20}") int maxConcurrent,
            @Value("${agent.semaphore.acquire-timeout:3000}") long acquireTimeoutMs,
            @Value("${agent.timeout.total:15000}") long totalTimeoutMs,
            EvaluateResultsTool evaluateResultsTool,
            QueryRewriteTool queryRewriteTool,
            @Value("${agent.evaluator.max-retry:1}") int maxRetryCount) {
        // 20个并发
        this.agentSemaphore = new Semaphore(maxConcurrent);
        this.agentPool = agentPool;
        this.toolPool = toolPool;
        this.plannerAgent = plannerAgent;
        this.knowledgeSearchTool = knowledgeSearchTool;
        this.acquireTimeoutMs = acquireTimeoutMs;
        this.totalTimeoutMs = totalTimeoutMs;
        this.evaluateResultsTool = evaluateResultsTool;
        this.queryRewriteTool = queryRewriteTool;
        this.maxRetryCount = maxRetryCount;
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
        AgentMDC.setPhase("plan");

        try {
            // Step 1: 规划子查询
            long planStart = System.currentTimeMillis();
            List<String> subQueries = plannerAgent.plan(message, history);
            long planLatency = System.currentTimeMillis() - planStart;
            logger.info("查询规划完成: subQueries={}, count={}, latency={}ms",
                subQueries, subQueries.size(), planLatency);

            // Step 2: 根据子查询数量动态分配每路检索配额，并行执行
            AgentMDC.setPhase("execute");
            int totalBudget = 10;
            int perQueryK = Math.max(1, totalBudget / subQueries.size());
            List<CompletableFuture<List<SearchResult>>> searchFutures = subQueries.stream()
                .map(query -> CompletableFuture.supplyAsync(
                    () -> knowledgeSearchTool.search(query, perQueryK), toolPool))
                .collect(Collectors.toList());

            long execStart = System.currentTimeMillis();
            CompletableFuture.allOf(searchFutures.toArray(new CompletableFuture[0])).join();
            long execLatency = System.currentTimeMillis() - execStart;

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

            logger.info("并行检索完成: subQueries={}, totalResults={}, latency={}ms",
                subQueries.size(), allResults.size(), execLatency);

            // Step 4: 评估结果质量（最多补充检索 maxRetryCount 次）
            AgentMDC.setPhase("evaluate");
            int retryCount = 0;
            while (retryCount < maxRetryCount) {
                // 构建结果摘要供评估器使用（前10条，每条截断到100字符）
                StringBuilder resultsSummary = new StringBuilder();
                for (int i = 0; i < Math.min(allResults.size(), 10); i++) {
                    SearchResult r = allResults.get(i);
                    String text = r.getTextContent();
                    if (text.length() > 100) text = text.substring(0, 100) + "...";
                    resultsSummary.append("[").append(i + 1).append("] ").append(text).append("\n");
                }

                EvaluateResultsTool.EvaluationResult eval =
                    evaluateResultsTool.evaluate(message, resultsSummary.toString());

                if (eval.sufficient()) {
                    logger.info("结果评估: sufficient=true, gap=null, retry={}", retryCount);
                    break;
                }

                retryCount++;
                logger.info("结果评估: sufficient=false, gap={}, retry={}", eval.gap(), retryCount);

                // 改写查询
                List<String> rewrittenQueries = queryRewriteTool.rewrite(message, eval.gap());

                // 补充检索时切回 execute 阶段
                AgentMDC.setPhase("execute");
                long supplementStart = System.currentTimeMillis();

                // 并行执行补充检索
                List<CompletableFuture<List<SearchResult>>> supplementFutures = rewrittenQueries.stream()
                    .map(query -> CompletableFuture.supplyAsync(
                        () -> knowledgeSearchTool.search(query, 3), toolPool))
                    .collect(Collectors.toList());

                CompletableFuture.allOf(supplementFutures.toArray(new CompletableFuture[0])).join();

                long supplementLatency = System.currentTimeMillis() - supplementStart;
                logger.info("补充检索完成: rewrittenQueries={}, latency={}ms",
                    rewrittenQueries.size(), supplementLatency);

                // 合并补充结果（去重）
                Set<String> existingKeys = allResults.stream()
                    .map(r -> r.getFileMd5() + ":" + r.getChunkId())
                    .collect(Collectors.toSet());

                for (CompletableFuture<List<SearchResult>> f : supplementFutures) {
                    for (SearchResult r : f.join()) {
                        String key = r.getFileMd5() + ":" + r.getChunkId();
                        if (!existingKeys.contains(key)) {
                            allResults.add(r);
                            existingKeys.add(key);
                        }
                    }
                }

                // 切回 evaluate 阶段
                AgentMDC.setPhase("evaluate");
            }

            // 总结
            AgentMDC.setPhase("synthesize");
            long totalLatency = System.currentTimeMillis() - startTime;
            logger.info("Agent执行完成: totalLatency={}ms, path=agent, subQueries={}, " +
                         "totalResults={}, supplementalRetry={}",
                totalLatency, subQueries.size(), allResults.size(), retryCount);

            return new AgentResult(allResults);

        } finally {
            AgentContext.clear();
        }
    }
}
