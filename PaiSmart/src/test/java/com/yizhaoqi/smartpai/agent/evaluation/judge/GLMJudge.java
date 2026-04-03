package com.yizhaoqi.smartpai.agent.evaluation.judge;

import java.util.concurrent.Semaphore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

/**
 * GLM 实现 LLM 评估器
 * 使用独立的 judgeChatClient 进行评估
 * 内置限流和重试机制
 */
@Component
public class GLMJudge implements LLMJudge {

    private static final Logger log = LoggerFactory.getLogger(GLMJudge.class);
    private static final int MAX_RETRIES = 3;

    private final ChatClient judgeClient;
    private final Semaphore rateLimiter;

    /**
     * 构造函数
     *
     * @param judgeClient 独立的评委 ChatClient
     */
    public GLMJudge(@Qualifier("judgeChatClient") ChatClient judgeClient) {
        this.judgeClient = judgeClient;
        // 使用 Semaphore 进行限流，限制并发请求数为 10
        this.rateLimiter = new Semaphore(10);
    }

    @Override
    public JudgeResult judge(String content) {
        int retries = MAX_RETRIES;
        while (retries > 0) {
            try {
                rateLimiter.acquire();
                try {
                    String response = judgeClient.prompt()
                        .user(content)
                        .call()
                        .content();

                    if (response != null && !response.isBlank()) {
                        return JudgeResult.success(response);
                    }
                } finally {
                    rateLimiter.release();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return JudgeResult.failed("Judge call interrupted: " + e.getMessage());
            } catch (Exception e) {
                log.warn("Judge call failed, retries left: {} - {}", retries - 1, e.getMessage());
            }
            retries--;
        }

        return JudgeResult.failed("Failed to get valid response after " + MAX_RETRIES + " retries");
    }
}
