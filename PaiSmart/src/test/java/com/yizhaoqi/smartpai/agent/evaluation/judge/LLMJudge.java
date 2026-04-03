package com.yizhaoqi.smartpai.agent.evaluation.judge;

/**
 * LLM 评估器接口
 * 用于使用大语言模型作为评判者（LLM-as-Judge）进行评估
 */
public interface LLMJudge {

    /**
     * 使用 LLM 评估内容
     *
     * @param content  待评估内容（完整 prompt）
     * @return 评估结果
     */
    JudgeResult judge(String content);

    /**
     * 评估并返回指定类型的 JSON 对象
     *
     * @param content 待评估内容
     * @param clazz   目标类型
     * @param <T>     泛型类型
     * @return 解析后的对象
     * @throws RuntimeException 如果评估失败或解析失败
     */
    default <T> T judgeAndParse(String content, Class<T> clazz) {
        JudgeResult result = judge(content);
        if (!result.success()) {
            throw new RuntimeException("Judge failed: " + result.errorMessage());
        }
        return result.parsedAs(clazz);
    }
}
