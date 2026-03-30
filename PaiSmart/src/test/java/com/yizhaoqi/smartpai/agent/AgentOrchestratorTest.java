package com.yizhaoqi.smartpai.agent;

import com.yizhaoqi.smartpai.agent.tool.EvaluateResultsTool;
import com.yizhaoqi.smartpai.agent.tool.KnowledgeSearchTool;
import com.yizhaoqi.smartpai.agent.tool.QueryRewriteTool;
import com.yizhaoqi.smartpai.entity.SearchResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AgentOrchestratorTest {

    @Mock private PlannerAgent plannerAgent;
    @Mock private KnowledgeSearchTool knowledgeSearchTool;
    @Mock private EvaluateResultsTool evaluateResultsTool;
    @Mock private QueryRewriteTool queryRewriteTool;

    private Executor agentPool;
    private Executor toolPool;
    private AgentOrchestrator orchestrator;

    @BeforeEach
    void setUp() {
        agentPool = Executors.newCachedThreadPool();
        toolPool = Executors.newCachedThreadPool();
        // Constructor matches actual source: agentPool, toolPool, plannerAgent, knowledgeSearchTool,
        // maxConcurrent, acquireTimeoutMs, totalTimeoutMs, evaluateResultsTool, queryRewriteTool, maxRetryCount
        // maxRetryCount=2 allows: evaluate#1 (insufficient) -> supplement -> evaluate#2 (sufficient)
        orchestrator = new AgentOrchestrator(
            agentPool, toolPool, plannerAgent, knowledgeSearchTool,
            20, 3000, 15000,
            evaluateResultsTool, queryRewriteTool, 2);
    }

    private SearchResult makeResult(String fileMd5, int chunkId, String text, double score) {
        return new SearchResult(fileMd5, chunkId, text, score);
    }

    @Test
    void executeAsync_sufficient_result_no_supplement() {
        when(plannerAgent.plan(anyString(), anyList())).thenReturn(List.of("年假政策是什么"));
        SearchResult r1 = makeResult("md5_1", 1, "公司年假政策：入职满1年享有5天年假...", 0.95);
        when(knowledgeSearchTool.search(eq("年假政策是什么"), anyInt()))
            .thenReturn(List.of(r1));
        when(evaluateResultsTool.evaluate(anyString(), anyString()))
            .thenReturn(new EvaluateResultsTool.EvaluationResult(true, null));

        AgentResult result = orchestrator.executeAsync("user1", "年假政策是什么", List.of()).join();

        assertNotNull(result);
        assertEquals(1, result.getSources().size());
        verify(queryRewriteTool, never()).rewrite(anyString(), anyString());
    }

    @Test
    void executeAsync_insufficient_result_triggers_supplement() {
        when(plannerAgent.plan(anyString(), anyList())).thenReturn(List.of("Q1和Q2对比"));
        SearchResult r1 = makeResult("md5_1", 1, "Q1季度报告摘要...", 0.85);
        when(knowledgeSearchTool.search(eq("Q1和Q2对比"), anyInt()))
            .thenReturn(List.of(r1));
        when(evaluateResultsTool.evaluate(anyString(), anyString()))
            .thenReturn(new EvaluateResultsTool.EvaluationResult(false, "缺少Q2数据"))
            .thenReturn(new EvaluateResultsTool.EvaluationResult(true, null));
        when(queryRewriteTool.rewrite(anyString(), eq("缺少Q2数据")))
            .thenReturn(List.of("Q2季度报告内容"));
        SearchResult r2 = makeResult("md5_2", 1, "Q2季度报告：营收增长20%...", 0.90);
        when(knowledgeSearchTool.search(eq("Q2季度报告内容"), anyInt()))
            .thenReturn(List.of(r2));

        AgentResult result = orchestrator.executeAsync("user1", "对比Q1和Q2", List.of()).join();

        assertNotNull(result);
        assertEquals(2, result.getSources().size());
        verify(queryRewriteTool, times(1)).rewrite(anyString(), eq("缺少Q2数据"));
        verify(evaluateResultsTool, times(2)).evaluate(anyString(), anyString());
    }

    @Test
    void executeAsync_supplement_deduplicates_results() {
        when(plannerAgent.plan(anyString(), anyList())).thenReturn(List.of("加班制度"));
        SearchResult r1 = makeResult("md5_1", 1, "加班制度...", 0.90);
        when(knowledgeSearchTool.search(eq("加班制度"), anyInt()))
            .thenReturn(List.of(r1));
        when(evaluateResultsTool.evaluate(anyString(), anyString()))
            .thenReturn(new EvaluateResultsTool.EvaluationResult(false, "缺少加班费标准"))
            .thenReturn(new EvaluateResultsTool.EvaluationResult(true, null));
        when(queryRewriteTool.rewrite(anyString(), eq("缺少加班费标准")))
            .thenReturn(List.of("加班费计算标准"));
        SearchResult r1dup = makeResult("md5_1", 1, "加班制度...", 0.88);
        when(knowledgeSearchTool.search(eq("加班费计算标准"), anyInt()))
            .thenReturn(List.of(r1dup));

        AgentResult result = orchestrator.executeAsync("user1", "加班制度", List.of()).join();

        assertNotNull(result);
        assertEquals(1, result.getSources().size());
    }

    @Test
    void executeAsync_evaluator_failure_falls_back_gracefully() {
        when(plannerAgent.plan(anyString(), anyList())).thenReturn(List.of("测试查询"));
        SearchResult r1 = makeResult("md5_1", 1, "测试内容...", 0.80);
        when(knowledgeSearchTool.search(eq("测试查询"), anyInt()))
            .thenReturn(List.of(r1));
        when(evaluateResultsTool.evaluate(anyString(), anyString()))
            .thenReturn(new EvaluateResultsTool.EvaluationResult(true, null));

        AgentResult result = orchestrator.executeAsync("user1", "测试", List.of()).join();

        assertNotNull(result);
        assertEquals(1, result.getSources().size());
        verify(queryRewriteTool, never()).rewrite(anyString(), anyString());
    }

    @Test
    void executeAsync_supplement_search_fails_throws_exception() {
        when(plannerAgent.plan(anyString(), anyList())).thenReturn(List.of("测试查询"));
        SearchResult r1 = makeResult("md5_1", 1, "部分结果...", 0.75);
        when(knowledgeSearchTool.search(eq("测试查询"), anyInt()))
            .thenReturn(List.of(r1));
        when(evaluateResultsTool.evaluate(anyString(), anyString()))
            .thenReturn(new EvaluateResultsTool.EvaluationResult(false, "缺少关键数据"))
            .thenReturn(new EvaluateResultsTool.EvaluationResult(true, null));
        when(queryRewriteTool.rewrite(anyString(), eq("缺少关键数据")))
            .thenReturn(List.of("补充查询"));
        when(knowledgeSearchTool.search(eq("补充查询"), anyInt()))
            .thenThrow(new RuntimeException("ES 连接失败"));

        var future = orchestrator.executeAsync("user1", "测试", List.of());
        assertThrows(java.util.concurrent.CompletionException.class, future::join);
    }
}
