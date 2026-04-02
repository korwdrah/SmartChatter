package com.yizhaoqi.smartpai.agent;

import org.slf4j.MDC;

import java.util.UUID;

/**
 * Agent MDC 上下文管理工具类。
 * 在请求入口设置 traceId/userId，在各阶段设置 agentPhase/agentPath，
 * 在请求出口统一清除。
 */
public class AgentMDC {

    /**
     * 在请求入口设置 MDC 上下文。
     * 所有路径（direct/rag/agent）都应该调用。
     */
    public static void setup(String userId) {
        MDC.put("traceId", UUID.randomUUID().toString().substring(0, 8));
        MDC.put("userId", userId);
    }

    /**
     * 设置当前 Agent 阶段。
     */
    public static void setPhase(String phase) {
        MDC.put("agentPhase", phase);
    }

    /**
     * 设置最终执行路径。
     */
    public static void setPath(String path) {
        MDC.put("agentPath", path);
    }

    /**
     * 在请求出口清除 MDC。
     */
    public static void clear() {
        MDC.remove("traceId");
        MDC.remove("userId");
        MDC.remove("agentPhase");
        MDC.remove("agentPath");
    }
}
