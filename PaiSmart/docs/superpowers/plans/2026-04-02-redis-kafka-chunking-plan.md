# Redis 滑动窗口 + Kafka 消费者修复 + 分块重叠 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 三个独立改进：Redis 聊天记录滑动窗口、Kafka 消费者问题修复、文件解析分块句子级重叠。

**Architecture:** Part 1 将 Redis String+JSON 改为 List+LTRIM；Part 2 修复 Kafka 消费者的 auto-commit、producer key、异常处理、幂等性等问题；Part 3 在现有四层切分后添加句子级 overlap 后处理。三个 Part 无依赖关系。

**Tech Stack:** Spring Boot 3.4.2, Spring Data Redis, Spring Kafka, Apache Tika, HanLP, JPA

**Spec:** `docs/superpowers/specs/2026-04-02-redis-sliding-window-kafka-chunking-overlap-design.md`

---

## File Structure

### Part 1: Redis 滑动窗口
| Action | File | Responsibility |
|--------|------|---------------|
| Modify | `src/main/java/com/yizhaoqi/smartpai/config/RedisConfig.java` | 新增 `chatRedisTemplate` bean |
| Modify | `src/main/java/com/yizhaoqi/smartpai/service/ChatHandler.java` | 重写 3 个 Redis 方法 |
| Modify | `src/main/java/com/yizhaoqi/smartpai/controller/ConversationController.java` | 适配 List 读取 |
| Modify | `src/main/java/com/yizhaoqi/smartpai/controller/AdminController.java` | 适配 List 读取 |
| Modify | `src/main/resources/application.yml` | 新增 `chat.history.max-messages` |
| Create | `src/test/java/com/yizhaoqi/smartpai/service/ChatHandlerRedisTest.java` | Redis 滑动窗口测试 |

### Part 2: Kafka 消费者修复
| Action | File | Responsibility |
|--------|------|---------------|
| Modify | `src/main/java/com/yizhaoqi/smartpai/config/KafkaConfig.java` | 恢复 auto-commit、设置 AckMode |
| Modify | `src/main/java/com/yizhaoqi/smartpai/consumer/FileProcessingConsumer.java` | 异常处理、幂等性检查 |
| Modify | `src/main/java/com/yizhaoqi/smartpai/controller/UploadController.java` | Producer 发送加 key |
| Modify | `src/main/java/com/yizhaoqi/smartpai/repository/DocumentVectorRepository.java` | 新增查询方法 |
| Modify | `src/main/resources/application-dev.yml` | 补充 `enable-auto-commit: false` |
| Modify | `src/main/resources/application-docker.yml` | 补充 `enable-auto-commit: false` |
| Create | `src/main/java/com/yizhaoqi/smartpai/consumer/DltConsumer.java` | DLQ 消费者 |
| Create | `src/test/java/com/yizhaoqi/smartpai/consumer/FileProcessingConsumerTest.java` | Kafka 消费者测试 |

### Part 3: 分块重叠
| Action | File | Responsibility |
|--------|------|---------------|
| Modify | `src/main/java/com/yizhaoqi/smartpai/service/ParseService.java` | 新增 overlap 逻辑 |
| Modify | `src/main/resources/application.yml` | 新增 `overlap-sentences` 配置 |
| Create | `src/test/java/com/yizhaoqi/smartpai/service/ParseServiceOverlapTest.java` | 分块重叠测试 |

---

## Part 1: Redis 滑动窗口

### Task 1: 新增 chatRedisTemplate Bean

**Files:**
- Modify: `src/main/java/com/yizhaoqi/smartpai/config/RedisConfig.java`

- [ ] **Step 1: 在 RedisConfig 中新增 chatRedisTemplate bean**

在 `RedisConfig.java` 的 `redisTemplate()` 方法下方（line 20 之后），新增：

```java
@Bean("chatRedisTemplate")
public RedisTemplate<String, String> chatRedisTemplate(RedisConnectionFactory connectionFactory) {
    RedisTemplate<String, String> template = new RedisTemplate<>();
    template.setConnectionFactory(connectionFactory);
    template.setKeySerializer(new StringRedisSerializer());
    template.setValueSerializer(new StringRedisSerializer());
    return template;
}
```

- [ ] **Step 2: 验证编译**

Run: `cd /Users/felx/Project/JavaProject/PaicodingProject/PaiSmart && mvn compile -q`
Expected: BUILD SUCCESS

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/yizhaoqi/smartpai/config/RedisConfig.java
git commit -m "feat(redis): 新增 chatRedisTemplate bean，使用 StringRedisSerializer"
```

---

### Task 2: 重写 ChatHandler 的 Redis 操作

**Files:**
- Modify: `src/main/java/com/yizhaoqi/smartpai/service/ChatHandler.java`
- Modify: `src/main/resources/application.yml`

> **关键说明**：`ChatHandler` 中有两类 Redis key：
> - `user:{userId}:current_conversation`：存储单个 conversationId 字符串，仍使用 `opsForValue()`
> - `conversation:{conversationId}`：存储消息列表，从 `opsForValue()` 改为 `opsForList()`

- [ ] **Step 1: 在 application.yml 中添加 chat.history.max-messages 配置**

在 `application.yml` 末尾（file.parsing 之后）新增：

```yaml
chat:
  history:
    max-messages: 20
```

- [ ] **Step 2: 修改 ChatHandler 的依赖注入**

将 `ChatHandler.java:43` 的字段声明：
```java
private final RedisTemplate<String, String> redisTemplate;
```
改为：
```java
private final RedisTemplate<String, String> chatRedisTemplate;
```

在构造函数（line 60-75）中，参数 `RedisTemplate<String, String> redisTemplate` 改为 `@Qualifier("chatRedisTemplate") RedisTemplate<String, String> chatRedisTemplate`。

新增 import（检查已有 import 避免重复）：
```java
import java.util.Collections;
```

新增配置字段（在 line 58 附近）：
```java
@Value("${chat.history.max-messages:20}")
private int maxMessages;
```

- [ ] **Step 3: 重写 getOrCreateConversationId**

将 `getOrCreateConversationId()` 方法（line 193-206）中所有 `redisTemplate` 替换为 `chatRedisTemplate`。核心逻辑不变（仍使用 `opsForValue()`，因为这是 user→conversationId 的简单映射），只需更换引用名。

- [ ] **Step 4: 重写 getConversationHistory**

将 `getConversationHistory(String conversationId)` 方法（line 207-222）替换为：

```java
private List<Map<String, String>> getConversationHistory(String conversationId, boolean reversed) {
    try {
        String key = "conversation:" + conversationId;
        List<String> elements = chatRedisTemplate.opsForList().range(key, 0, -1);
        if (elements == null || elements.isEmpty()) {
            if (Boolean.TRUE.equals(chatRedisTemplate.hasKey(key))) {
                logger.warn("存在旧格式(String)对话数据 key={}, 将在 TTL 到期后自动清理", key);
            }
            return new ArrayList<>();
        }
        List<Map<String, String>> history = new ArrayList<>();
        for (String element : elements) {
            history.add(objectMapper.readValue(element, new TypeReference<Map<String, String>>() {}));
        }
        if (reversed) {
            Collections.reverse(history);
        }
        return history;
    } catch (Exception e) {
        logger.error("读取对话历史失败: conversationId={}", conversationId, e);
        return new ArrayList<>();
    }
}
```

- [ ] **Step 5: 重写 updateConversationHistory**

将 `updateConversationHistory()` 方法（line 224-257）替换为：

```java
private void updateConversationHistory(String conversationId, String userMessage, String response) {
    try {
        String key = "conversation:" + conversationId;
        String timestamp = java.time.LocalDateTime.now().toString();

        Map<String, String> userMsg = new HashMap<>();
        userMsg.put("role", "user");
        userMsg.put("content", userMessage);
        userMsg.put("timestamp", timestamp);

        Map<String, String> assistantMsg = new HashMap<>();
        assistantMsg.put("role", "assistant");
        assistantMsg.put("content", response);
        assistantMsg.put("timestamp", timestamp);

        String userJson = objectMapper.writeValueAsString(userMsg);
        String assistantJson = objectMapper.writeValueAsString(assistantMsg);

        // 使用 chatRedisTemplate 的 serializer 保证编码一致
        byte[] keyBytes = chatRedisTemplate.getStringSerializer().serialize(key);
        byte[] userBytes = chatRedisTemplate.getStringSerializer().serialize(userJson);
        byte[] assistantBytes = chatRedisTemplate.getStringSerializer().serialize(assistantJson);
        long maxMsg = maxMessages;

        chatRedisTemplate.executePipelined((org.springframework.data.redis.connection.RedisCallback<Object>) connection -> {
            connection.listCommands().rPush(keyBytes, userBytes);
            connection.listCommands().rPush(keyBytes, assistantBytes);
            connection.listCommands().lTrim(keyBytes, -maxMsg, -1);
            connection.keyCommands().expire(keyBytes, Duration.ofDays(7).getSeconds());
            return null;
        });
    } catch (Exception e) {
        logger.error("更新对话历史失败: conversationId={}", conversationId, e);
    }
}
```

> 注意：使用 `chatRedisTemplate.getStringSerializer().serialize()` 而非 `key.getBytes()`，确保与 StringRedisSerializer 编码一致。

- [ ] **Step 6: 更新 processMessage 中的调用**

在 `processMessage()` 中（约 line 91 附近），`getConversationHistory` 的调用改为 `getConversationHistory(conversationId, true)`（LLM 上下文需要最新优先）。

移除 `updateConversationHistory` 内部对 `getConversationHistory` 的调用（原逻辑中先读取历史再追加，新逻辑不需要读取）。

- [ ] **Step 7: 验证编译**

Run: `cd /Users/felx/Project/JavaProject/PaicodingProject/PaiSmart && mvn compile -q`
Expected: BUILD SUCCESS

- [ ] **Step 8: Commit**

```bash
git add src/main/java/com/yizhaoqi/smartpai/service/ChatHandler.java src/main/resources/application.yml
git commit -m "feat(chat): Redis 聊天记录改为 List+LTRIM 滑动窗口"
```

---

### Task 3: 更新 ConversationController 适配 List 读取

**Files:**
- Modify: `src/main/java/com/yizhaoqi/smartpai/controller/ConversationController.java`

> **关键说明**：`ConversationController` 同时读取 `user:*:current_conversation`（仍用 `opsForValue()`）和 `conversation:*`（改用 `opsForList()`）。

- [ ] **Step 1: 修改 RedisTemplate 注入**

将 `ConversationController.java:29-30` 的：
```java
@Autowired
private RedisTemplate<String, String> redisTemplate;
```
改为：
```java
@Autowired
@Qualifier("chatRedisTemplate")
private RedisTemplate<String, String> redisTemplate;
```

添加 import（`TypeReference` 已存在于 line 5，不需要重复添加）：
```java
import org.springframework.beans.factory.annotation.Qualifier;
```

- [ ] **Step 2: 重写 getConversationsFromRedis 方法**

在 `getConversationsFromRedis()` 方法（line 113-206）中，找到读取 `conversation:{id}` key 的部分（约 line 150-160），将：
```java
String json = redisTemplate.opsForValue().get(conversationKey);
List<Map<String, String>> history = objectMapper.readValue(json, ...);
```
改为：
```java
List<String> elements = redisTemplate.opsForList().range(conversationKey, 0, -1);
List<Map<String, String>> history = new ArrayList<>();
if (elements != null) {
    for (String element : elements) {
        history.add(objectMapper.readValue(element, new TypeReference<Map<String, String>>() {}));
    }
}
```

其余逻辑（读取 `user:*:current_conversation` 用 `opsForValue()`、日期过滤、消息格式化）不变。

- [ ] **Step 3: 验证编译**

Run: `cd /Users/felx/Project/JavaProject/PaicodingProject/PaiSmart && mvn compile -q`
Expected: BUILD SUCCESS

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/yizhaoqi/smartpai/controller/ConversationController.java
git commit -m "feat(chat): ConversationController 适配 Redis List 读取"
```

---

### Task 4: 更新 AdminController 适配 List 读取

**Files:**
- Modify: `src/main/java/com/yizhaoqi/smartpai/controller/AdminController.java`

- [ ] **Step 1: 修改 RedisTemplate 注入**

将 `AdminController.java:43-44` 的：
```java
@Autowired
private RedisTemplate<String, String> redisTemplate;
```
改为：
```java
@Autowired
@Qualifier("chatRedisTemplate")
private RedisTemplate<String, String> redisTemplate;
```

添加 import：
```java
import org.springframework.beans.factory.annotation.Qualifier;
import com.fasterxml.jackson.core.type.TypeReference;
```

> 注：检查 `TypeReference` 是否已存在。如果未导入，需添加。

- [ ] **Step 2: 修改 getAllConversations 中的读取逻辑**

在 `getAllConversations()` 方法（line 416-504）中，将 line 476-478 的：
```java
String json = redisTemplate.opsForValue().get(conversationKey);
if (json != null) {
    processRedisConversation(json, allConversations, displayUsername, start_date, end_date);
}
```
改为：
```java
List<String> elements = redisTemplate.opsForList().range(conversationKey, 0, -1);
if (elements != null && !elements.isEmpty()) {
    processRedisConversation(elements, allConversations, displayUsername, start_date, end_date);
}
```

- [ ] **Step 3: 重写 processRedisConversation 方法签名和实现**

将 `processRedisConversation(String json, ...)` 方法（line 509-569）的签名改为：
```java
private void processRedisConversation(List<String> elements, List<Map<String, Object>> targetList,
        String username, String startDate, String endDate) throws JsonProcessingException {
```

将内部解析逻辑从：
```java
List<Map<String, String>> history = objectMapper.readValue(json, ...);
```
改为：
```java
List<Map<String, String>> history = new ArrayList<>();
for (String element : elements) {
    history.add(objectMapper.readValue(element, new TypeReference<Map<String, String>>() {}));
}
```

其余逻辑（日期过滤、消息格式化）不变。

- [ ] **Step 4: 验证编译**

Run: `cd /Users/felx/Project/JavaProject/PaicodingProject/PaiSmart && mvn compile -q`
Expected: BUILD SUCCESS

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/yizhaoqi/smartpai/controller/AdminController.java
git commit -m "feat(chat): AdminController 适配 Redis List 读取"
```

---

## Part 2: Kafka 消费者修复

### Task 5: 修复 KafkaConfig — auto-commit + AckMode

**Files:**
- Modify: `src/main/java/com/yizhaoqi/smartpai/config/KafkaConfig.java`
- Modify: `src/main/resources/application-dev.yml`
- Modify: `src/main/resources/application-docker.yml`

- [ ] **Step 1: 取消注释 ENABLE_AUTO_COMMIT_CONFIG**

在 `KafkaConfig.java:78`（`consumerFactory()` 方法内），取消注释：
```java
config.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
```

- [ ] **Step 2: 设置 AckMode.MANUAL_IMMEDIATE**

在 `KafkaConfig.java` 的 `kafkaListenerContainerFactory()` 方法中（line 88-106），在 `factory.setConcurrency(3)` 之后（line 104 附近）添加：
```java
factory.getContainerProperties().setAckMode(org.springframework.kafka.listener.ContainerProperties.AckMode.MANUAL_IMMEDIATE);
```

- [ ] **Step 3: 在 dev profile 中补充 enable-auto-commit**

在 `application-dev.yml` 的 `consumer` 部分，在 `properties` 块之后、`topic` 之前添加。注意缩进必须与 `group-id`、`auto-offset-reset` 同级（6 个空格）：

```yaml
      enable-auto-commit: false
```

完整 consumer 部分应为：
```yaml
    consumer:
      group-id: file-processing-group
      auto-offset-reset: earliest
      key-deserializer: org.apache.kafka.common.serialization.StringDeserializer
      value-deserializer: org.springframework.kafka.support.serializer.JsonDeserializer
      enable-auto-commit: false
      properties:
        spring.json.trusted.packages: "*"
        client.dns.lookup: use_all_dns_ips
    topic:
```

- [ ] **Step 4: 在 docker profile 中补充 enable-auto-commit**

同上，在 `application-docker.yml` 的 `consumer` 部分添加 `enable-auto-commit: false`，缩进与 `group-id` 同级。

- [ ] **Step 5: 验证编译**

Run: `cd /Users/felx/Project/JavaProject/PaicodingProject/PaiSmart && mvn compile -q`
Expected: BUILD SUCCESS

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/yizhaoqi/smartpai/config/KafkaConfig.java src/main/resources/application-dev.yml src/main/resources/application-docker.yml
git commit -m "fix(kafka): 恢复 auto-commit 禁用 + 设置 AckMode.MANUAL_IMMEDIATE"
```

---

### Task 6: 修复 Producer 消息加 key

**Files:**
- Modify: `src/main/java/com/yizhaoqi/smartpai/controller/UploadController.java`

- [ ] **Step 1: 修改 send 调用添加 fileMd5 作为 key**

在 `UploadController.java:297`，将：
```java
kt.send(kafkaConfig.getFileProcessingTopic(), task);
```
改为：
```java
kt.send(kafkaConfig.getFileProcessingTopic(), task.getFileMd5(), task);
```

- [ ] **Step 2: 验证编译**

Run: `cd /Users/felx/Project/JavaProject/PaicodingProject/PaiSmart && mvn compile -q`
Expected: BUILD SUCCESS

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/yizhaoqi/smartpai/controller/UploadController.java
git commit -m "fix(kafka): Producer 发送消息添加 fileMd5 作为 key"
```

---

### Task 7: 修复 downloadFileFromStorage 异常处理 + 统一注入

**Files:**
- Modify: `src/main/java/com/yizhaoqi/smartpai/consumer/FileProcessingConsumer.java`

- [ ] **Step 1: 修改 downloadFileFromStorage 方法**

在 `FileProcessingConsumer.java` 的 `downloadFileFromStorage()` 方法（line 96-138）中：

1. 移除方法签名上的所有 checked exception 声明，简化为 `throws IOException`：
```java
private InputStream downloadFileFromStorage(String filePath) throws IOException {
```

2. 将 line 134-136 的异常吞没（`catch (Exception e) { return null; }`）替换为包装后重新抛出：
```java
} catch (IOException e) {
    throw new IOException("下载文件失败: " + filePath, e);
} catch (Exception e) {
    throw new IOException("下载文件时发生异常: " + filePath, e);
}
```

3. 移除方法内部的 try-catch 中 `return null` 分支和中间的其他 checked exception 声明。所有异常最终包装为 IOException 抛出。

- [ ] **Step 2: 修改 processTask 中的调用**

在 `processTask()` 方法中（line 52 附近），移除对 `downloadFileFromStorage` 返回值的 null 检查（因为异常直接抛出，不会返回 null）。简化为：
```java
InputStream inputStream = downloadFileFromStorage(task.getFilePath());
```

移除 line 54-56 的 null 检查逻辑（`if (inputStream == null)` 分支）。

- [ ] **Step 3: 统一注入方式**

将 `FileProcessingConsumer.java:28-29` 的：
```java
@Autowired
private KafkaConfig kafkaConfig;
```
改为构造函数注入，在构造函数中添加 `KafkaConfig kafkaConfig` 参数。

- [ ] **Step 4: 验证编译**

Run: `cd /Users/felx/Project/JavaProject/PaicodingProject/PaiSmart && mvn compile -q`
Expected: BUILD SUCCESS

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/yizhaoqi/smartpai/consumer/FileProcessingConsumer.java
git commit -m "fix(kafka): downloadFileFromStorage 不再吞掉异常，统一注入方式"
```

---

### Task 8: 添加消费端幂等性

**Files:**
- Modify: `src/main/java/com/yizhaoqi/smartpai/repository/DocumentVectorRepository.java`
- Modify: `src/main/java/com/yizhaoqi/smartpai/consumer/FileProcessingConsumer.java`

- [ ] **Step 1: 在 DocumentVectorRepository 新增方法**

在 `DocumentVectorRepository.java`（line 22 之后）新增：

```java
boolean existsByUserIdAndFileMd5(String userId, String fileMd5);

@Transactional
@Modifying
@Query(value = "DELETE FROM document_vectors WHERE user_id = ?1 AND file_md5 = ?2", nativeQuery = true)
void deleteByUserIdAndFileMd5(String userId, String fileMd5);
```

- [ ] **Step 2: 在 FileProcessingConsumer 中注入 DocumentVectorRepository**

在构造函数中添加 `DocumentVectorRepository documentVectorRepository` 参数，保存为 final 字段。

- [ ] **Step 3: 在 processTask 中添加幂等性检查**

在 `processTask()` 方法中，在 `downloadFileFromStorage` 调用之前，新增：

```java
// 幂等性检查：如果该用户已处理过该文件，先清理再重处理
if (documentVectorRepository.existsByUserIdAndFileMd5(task.getUserId(), task.getFileMd5())) {
    log.warn("文件已存在处理记录，清理后重新处理: fileMd5={}, userId={}", task.getFileMd5(), task.getUserId());
    documentVectorRepository.deleteByUserIdAndFileMd5(task.getUserId(), task.getFileMd5());
    // TODO: 同步清理 ES 中对应数据（需调用 elasticsearchService.deleteByUserIdAndFileMd5）
}
```

- [ ] **Step 4: 验证编译**

Run: `cd /Users/felx/Project/JavaProject/PaicodingProject/PaiSmart && mvn compile -q`
Expected: BUILD SUCCESS

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/yizhaoqi/smartpai/repository/DocumentVectorRepository.java src/main/java/com/yizhaoqi/smartpai/consumer/FileProcessingConsumer.java
git commit -m "feat(kafka): 消费端幂等性检查，基于 userId+fileMd5 去重"
```

---

### Task 9: 新增 DLQ 消费者

**Files:**
- Create: `src/main/java/com/yizhaoqi/smartpai/consumer/DltConsumer.java`

- [ ] **Step 1: 创建 DltConsumer**

```java
package com.yizhaoqi.smartpai.consumer;

import com.yizhaoqi.smartpai.model.FileProcessingTask;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

@Component
@Slf4j
public class DltConsumer {

    @KafkaListener(
        topics = "${spring.kafka.topic.dlt}",
        groupId = "${spring.kafka.consumer.group-id}-dlt",
        containerFactory = "kafkaListenerContainerFactory"
    )
    public void handleDltMessage(FileProcessingTask task, Acknowledgment ack) {
        log.error("文件处理失败（DLQ）: fileMd5={}, fileName={}, userId={}, filePath={}",
                task.getFileMd5(), task.getFileName(), task.getUserId(), task.getFilePath());
        // TODO: 可选 — 更新文件状态为 FAILED、发送告警通知
        ack.acknowledge();
    }
}
```

> 注意：`DeadLetterPublishingRecoverer` 会保留原始 headers，`JsonDeserializer` 应能正常反序列化。如果遇到反序列化问题，可改用 `ConsumerRecord<byte[], byte[]>` 接收后手动反序列化。

- [ ] **Step 2: 验证编译**

Run: `cd /Users/felx/Project/JavaProject/PaicodingProject/PaiSmart && mvn compile -q`
Expected: BUILD SUCCESS

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/yizhaoqi/smartpai/consumer/DltConsumer.java
git commit -m "feat(kafka): 新增 DLQ 消费者，记录失败消息日志"
```

---

## Part 3: 分块句子级重叠

### Task 10: 添加 overlap 配置、辅助方法并改为 package-private

**Files:**
- Modify: `src/main/resources/application.yml`
- Modify: `src/main/java/com/yizhaoqi/smartpai/service/ParseService.java`

- [ ] **Step 1: 在 application.yml 中添加 overlap-sentences 配置**

在 `application.yml` 的 `file.parsing` 部分（line 85 之后）新增：
```yaml
    overlap-sentences: 2
```

完整效果：
```yaml
file:
  parsing:
    chunk-size: 512
    buffer-size: 8192
    max-memory-threshold: 0.8
    overlap-sentences: 2
```

- [ ] **Step 2: 在 ParseService 中添加配置字段**

在 `ParseService.java` 的字段声明区（line 41 之后），新增：
```java
@Value("${file.parsing.overlap-sentences:2}")
private int overlapSentences;
```

- [ ] **Step 3: 新增 extractLastNSentences 方法（package-private）**

在 `ParseService.java` 中（`splitByCharacters` 方法之后，line 346 附近），新增：

```java
/**
 * 从文本尾部提取最后 N 个完整句子。
 * 使用与 splitLongParagraph 相同的句子正则。
 * 如果文本中没有句子分隔符，返回包含整个文本的单元素列表。
 */
List<String> extractLastNSentences(String text, int n) {
    if (text == null || text.isEmpty() || n <= 0) {
        return new ArrayList<>();
    }
    String[] sentences = text.split("(?<=[。！？；])|(?<=[.!?;])\\s+");
    List<String> result = new ArrayList<>();
    int start = Math.max(0, sentences.length - n);
    for (int i = start; i < sentences.length; i++) {
        String s = sentences[i].trim();
        if (!s.isEmpty()) {
            result.add(s);
        }
    }
    return result;
}
```

> 注意：`private` 改为 package-private（无修饰符），以便同包测试类直接调用。

- [ ] **Step 4: 新增 applyOverlap 方法（package-private）**

在 `extractLastNSentences` 之后，新增：

```java
/**
 * 对切分后的 chunks 应用句子级重叠。
 * 每个非首 chunk 头部拼上前一个 chunk 尾部的 N 个句子。
 */
List<String> applyOverlap(List<String> chunks) {
    if (chunks == null || chunks.size() <= 1 || overlapSentences <= 0) {
        return chunks;
    }
    List<String> result = new ArrayList<>(chunks.size());
    result.add(chunks.get(0)); // 第一个 chunk 无前缀
    for (int i = 1; i < chunks.size(); i++) {
        List<String> overlapSentencesList = extractLastNSentences(chunks.get(i - 1), overlapSentences);
        if (overlapSentencesList.isEmpty()) {
            result.add(chunks.get(i));
        } else {
            String overlapPrefix = String.join("", overlapSentencesList);
            result.add(overlapPrefix + chunks.get(i));
        }
    }
    return result;
}
```

- [ ] **Step 5: 验证编译**

Run: `cd /Users/felx/Project/JavaProject/PaicodingProject/PaiSmart && mvn compile -q`
Expected: BUILD SUCCESS

- [ ] **Step 6: Commit**

```bash
git add src/main/resources/application.yml src/main/java/com/yizhaoqi/smartpai/service/ParseService.java
git commit -m "feat(parse): 新增 overlap 配置和 extractLastNSentences + applyOverlap 方法"
```

---

### Task 11: 编写 overlap 测试（TDD — 先写测试验证方法行为）

**Files:**
- Create: `src/test/java/com/yizhaoqi/smartpai/service/ParseServiceOverlapTest.java`

- [ ] **Step 1: 编写完整可运行的测试**

```java
package com.yizhaoqi.smartpai.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.MockitoAnnotations;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ParseServiceOverlapTest {

    private ParseService parseService;

    @BeforeEach
    void setUp() {
        parseService = new ParseService();
        ReflectionTestUtils.setField(parseService, "overlapSentences", 2);
    }

    @Test
    void extractLastNSentences_chineseText_returnsLast2Sentences() {
        List<String> result = parseService.extractLastNSentences(
                "这是第一句。这是第二句。这是第三句。这是第四句。", 2);
        assertEquals(2, result.size());
        assertEquals("这是第三句。", result.get(0));
        assertEquals("这是第四句。", result.get(1));
    }

    @Test
    void extractLastNSentences_noDelimiters_returnsWholeText() {
        List<String> result = parseService.extractLastNSentences("没有句号的文本", 2);
        assertEquals(1, result.size());
        assertEquals("没有句号的文本", result.get(0));
    }

    @Test
    void extractLastNSentences_emptyText_returnsEmpty() {
        List<String> result = parseService.extractLastNSentences("", 2);
        assertTrue(result.isEmpty());
    }

    @Test
    void extractLastNSentences_nullText_returnsEmpty() {
        List<String> result = parseService.extractLastNSentences(null, 2);
        assertTrue(result.isEmpty());
    }

    @Test
    void extractLastNSentences_englishText_returnsLast2Sentences() {
        List<String> result = parseService.extractLastNSentences(
                "First sentence. Second sentence. Third sentence. Fourth sentence.", 2);
        assertEquals(2, result.size());
        assertEquals("Second sentence.", result.get(0));
        assertEquals("Third sentence. Fourth sentence.", result.get(1));
    }

    @Test
    void applyOverlap_singleChunk_returnsUnchanged() {
        List<String> chunks = List.of("唯一的一个块");
        List<String> result = parseService.applyOverlap(chunks);
        assertEquals(1, result.size());
        assertEquals("唯一的一个块", result.get(0));
    }

    @Test
    void applyOverlap_multipleChunks_hasOverlap() {
        List<String> chunks = List.of(
                "第一段内容。第二段内容。第三段内容。",
                "第四段内容。第五段内容。"
        );
        List<String> result = parseService.applyOverlap(chunks);
        assertEquals(2, result.size());
        assertEquals("第一段内容。第二段内容。第三段内容。", result.get(0));
        // chunk2 头部应有 chunk1 的最后 2 句
        assertTrue(result.get(1).startsWith("第二段内容。第三段内容。"));
        assertTrue(result.get(1).endsWith("第四段内容。第五段内容。"));
    }

    @Test
    void applyOverlap_nullOrEmpty_returnsInput() {
        assertNull(parseService.applyOverlap(null));
        assertEquals(List.of("single"), parseService.applyOverlap(List.of("single")));
    }
}
```

- [ ] **Step 2: 运行测试验证通过**

Run: `cd /Users/felx/Project/JavaProject/PaicodingProject/PaiSmart && mvn test -Dtest=ParseServiceOverlapTest -q`
Expected: 所有测试通过

- [ ] **Step 3: Commit**

```bash
git add src/test/java/com/yizhaoqi/smartpai/service/ParseServiceOverlapTest.java
git commit -m "test(parse): 新增 extractLastNSentences + applyOverlap 单元测试"
```

---

### Task 12: 修改 StreamingContentHandler 集成 overlap

**Files:**
- Modify: `src/main/java/com/yizhaoqi/smartpai/service/ParseService.java`

> **关键说明**：`StreamingContentHandler` 是 `ParseService` 的私有内部类（line 122），通过 `ParseService.this` 访问外部类。`saveChildChunks` 实际签名接受 6 个参数：`(String fileMd5, List<String> chunks, String userId, String orgTag, boolean isPublic, int startingChunkId)`。

- [ ] **Step 1: 在 StreamingContentHandler 内部类中添加 trailingOverlap 字段**

在 `StreamingContentHandler` 内部类（line 122 开始）的字段区，新增：
```java
private String trailingOverlap = "";
```

- [ ] **Step 2: 修改 processParentChunk 方法**

将 `processParentChunk()` 方法（line 154-166）改为：

```java
private void processParentChunk() {
    String parentChunkText = buffer.toString();
    buffer.setLength(0);  // 必须清空 buffer

    // 1. 正常切分
    List<String> childChunks = ParseService.this.splitTextIntoChunksWithSemantics(parentChunkText, chunkSize);

    // 2. 应用句子级 overlap
    childChunks = ParseService.this.applyOverlap(childChunks);

    // 3. 跨 parent chunk 边界：将上一个 parent chunk 的尾部 overlap 拼到第一个 chunk
    if (!trailingOverlap.isEmpty() && !childChunks.isEmpty()) {
        childChunks.set(0, trailingOverlap + childChunks.get(0));
    }

    // 4. 更新 trailingOverlap 为当前最后一个 chunk 的尾部句子
    if (!childChunks.isEmpty()) {
        List<String> lastSentences = ParseService.this.extractLastNSentences(
                childChunks.get(childChunks.size() - 1), ParseService.this.overlapSentences);
        trailingOverlap = lastSentences.isEmpty() ? "" : String.join("", lastSentences);
    }

    // 5. 保存 chunks（保留原有的完整参数列表）
    this.savedChunkCount = ParseService.this.saveChildChunks(
            fileMd5, childChunks, userId, orgTag, isPublic, this.savedChunkCount);
    logger.info("处理父块完成，生成 {} 个子块", childChunks.size());
}
```

> 注意：
> - `buffer.setLength(0)` 必须保留，否则 buffer 无限增长导致 OOM
> - `saveChildChunks` 保留完整的 6 参数调用，与原代码一致
> - `overlapSentences` 通过 `ParseService.this.overlapSentences` 访问
> - `chunkSize`、`fileMd5`、`userId`、`orgTag`、`isPublic` 都是内部类的现有字段

- [ ] **Step 3: 验证编译**

Run: `cd /Users/felx/Project/JavaProject/PaicodingProject/PaiSmart && mvn compile -q`
Expected: BUILD SUCCESS

- [ ] **Step 4: 运行 overlap 测试确认不破坏**

Run: `cd /Users/felx/Project/JavaProject/PaicodingProject/PaiSmart && mvn test -Dtest=ParseServiceOverlapTest -q`
Expected: 所有测试通过

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/yizhaoqi/smartpai/service/ParseService.java
git commit -m "feat(parse): StreamingContentHandler 集成句子级 overlap 后处理"
```

---

## 总结

| Part | Tasks | 预计改动文件数 |
|------|-------|--------------|
| Part 1: Redis 滑动窗口 | Task 1-4 | 6 个文件 |
| Part 2: Kafka 消费者修复 | Task 5-9 | 8 个文件 |
| Part 3: 分块重叠 | Task 10-12 | 3 个文件 |

**实施顺序建议**：Part 2 → Part 3 → Part 1（先修 Kafka 基础设施，再改解析逻辑，最后改 Redis。但三个 Part 无硬依赖，可按任意顺序执行）
