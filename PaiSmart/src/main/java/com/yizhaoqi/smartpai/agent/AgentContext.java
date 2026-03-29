package com.yizhaoqi.smartpai.agent;

/**
 * Agent 执行上下文，通过 ThreadLocal 在线程间传递 userId 等信息。
 * 在 AgentOrchestrator 入口设置，在出口清除。
 */
public class AgentContext {

    private static final ThreadLocal<String> USER_ID = new ThreadLocal<>();

    public static void setUserId(String userId) {
        USER_ID.set(userId);
    }

    public static String getCurrentUserId() {
        return USER_ID.get();
    }

    public static void clear() {
        USER_ID.remove();
    }
}
