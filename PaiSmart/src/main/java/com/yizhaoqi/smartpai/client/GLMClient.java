package com.yizhaoqi.smartpai.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yizhaoqi.smartpai.config.AiProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

@Service
public class GLMClient {
    private final WebClient webClient;
    private final String apiKey;
    private final String model;
    private final AiProperties aiProperties;
    private static final Logger logger = LoggerFactory.getLogger(GLMClient.class);

    public GLMClient(@Value("${glm.api.url}") String apiUrl,
                   @Value("${glm.api.key}") String apiKey,
                   @Value("${glm.api.model}") String model,
                   AiProperties aiProperties) {
        WebClient.Builder builder = WebClient.builder().baseUrl(apiUrl);
        //保证key不为空时才传入
        if (apiKey != null && !apiKey.trim().isEmpty()) {
            builder.defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey);
        }

        this.apiKey = apiKey;
        this.model = model;
        this.webClient = builder.build();
        this.aiProperties = aiProperties;
    }

    public void streamResponse(String userMessage,
                             String context,
                             List<Map<String, String>> history,
                             Consumer<String> onChunk,
                             Consumer<Throwable> onError) {
        Map<String, Object> request = buildRequest(userMessage, context, history);
        webClient.post()
                .uri("/chat/completions")
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .bodyValue(request)
                .retrieve()
                .bodyToFlux(String.class)
                .subscribe(
                        chunk -> processChunk(chunk, onChunk),
                        onError
                );
    }

    private void processChunk(String chunk, Consumer<String> onChunk) {
        try{
            //检查是否是结束标记
            if("[DONE]".equals(chunk)){
                logger.debug("对话结束");
                return;
            }
            //解析Json
            ObjectMapper mapper = new ObjectMapper();
            JsonNode node = mapper.readTree(chunk);
            String content = node.path("choices")
                    .path(0)
                    .path("delta")
                    .path("content")
                    .asText("");
            if(!content.isEmpty()){
                onChunk.accept(content);
            }
        }
        catch (Exception e){
            logger.error("处理数据块时出错: {}", e.getMessage(), e);
        }
    }

    private Map<String, Object> buildRequest(String userMessage, String context, List<Map<String, String>> history) {
        logger.info("构建请求，用户消息：{}，上下文长度：{}，历史消息数：{}",
                userMessage,
                context != null ? context.length() : 0,
                history != null ? history.size() : 0);

        Map<String, Object> request = new HashMap<>();
        request.put("model", model);
        request.put("messages", buildMessages(userMessage, context, history));
        request.put("stream", true);
        //生成参数
        AiProperties.Generation generation = aiProperties.getGeneration();
        if(generation.getTemperature() != null){
            request.put("temperature", generation.getTemperature());
        }
        if(generation.getTopP() != null){
            request.put("top_p", generation.getTopP());
        }
        if(generation.getMaxTokens() != null){
            request.put("max_tokens", generation.getMaxTokens());
        }
        return request;
    }

    private List<Map<String, String>> buildMessages(String userMessage, String context, List<Map<String, String>> history) {
        List<Map<String, String>> messages = new ArrayList<>();
        AiProperties.Prompt promptCfg = aiProperties.getPrompt();

        //1.构建System指令（rule + refer）
        StringBuilder sysBuilder = new StringBuilder();
        String rules = promptCfg.getRules();
        if (rules != null) {
            sysBuilder.append(rules).append("\n\n");
        }
        String refStart = promptCfg.getRefStart() != null ? promptCfg.getRefStart() : "<<REF>>";
        String refEnd = promptCfg.getRefEnd() != null ? promptCfg.getRefEnd() : "<<END>>";
        sysBuilder.append(refStart).append("\n");

        if(context != null && !context.isEmpty()) {
            sysBuilder.append(context).append("\n");
        }
        else {
            //占位
            String noResult = promptCfg.getNoResultText() != null ? promptCfg.getNoResultText() : "（本轮无检索结果）";
            sysBuilder.append(noResult).append("\n");
        }

        sysBuilder.append(refEnd);
        //日志
        String systemContent = sysBuilder.toString();
        messages.add(Map.of(
                "role", "system",
                "content", systemContent
        ));
        logger.debug("添加了系统消息，长度: {}", systemContent.length());
        //2.追加历史消息
        if(history != null && !history.isEmpty()){
            messages.addAll(history);
        }

        //3.当前用户问题
        messages.add(Map.of(
                "role","user",
                "content", userMessage
        ));
        return messages;

    }
}
