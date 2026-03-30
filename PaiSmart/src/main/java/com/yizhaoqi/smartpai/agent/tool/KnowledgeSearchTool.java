package com.yizhaoqi.smartpai.agent.tool;

import com.yizhaoqi.smartpai.agent.AgentContext;
import com.yizhaoqi.smartpai.entity.SearchResult;
import com.yizhaoqi.smartpai.service.HybridSearchService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class KnowledgeSearchTool {

    private static final Logger logger = LoggerFactory.getLogger(KnowledgeSearchTool.class);
    private final HybridSearchService hybridSearchService;

    public KnowledgeSearchTool(HybridSearchService hybridSearchService) {
        this.hybridSearchService = hybridSearchService;
    }

    /**
     * 在知识库中检索相关文档。
     * userId 从 AgentContext (ThreadLocal) 获取，确保权限过滤生效。
     */
    public List<SearchResult> search(String query, int topK) {
        String userId = AgentContext.getCurrentUserId();
        logger.debug("KnowledgeSearchTool: query={}, topK={}, userId={}", query, topK, userId);

        List<SearchResult> results;
        if (userId != null) {
            results = hybridSearchService.searchWithPermission(query, userId, topK);
        } else {
            // 兜底: 无 userId 时使用无权限过滤的搜索
            results = hybridSearchService.search(query, topK);
        }

        logger.debug("KnowledgeSearchTool: 检索完成, 结果数={}", results.size());
        return results;
    }
}
