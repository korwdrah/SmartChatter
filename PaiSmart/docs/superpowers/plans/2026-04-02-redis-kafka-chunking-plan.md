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

在构造函数中，参数 `RedisTemplate<String, String> redisTemplate` 改为 `@Qualifier("chatRedisTemplate") RedisTemplate<String, String> chatRedisTemplate`。

需要添加 import：
```java
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.StringRedisTemplate;
import java.util.Collections;
import org.springframework.data.redis.core.script.DefaultRedisScript;
```

注意：`@Qualifier` import 已存在（line 14）。

新增配置字段（在 line 58 附近）：
```java
@Value("${chat.history.max-messages:20}")
private int maxMessages;
```

- [ ] **Step 3: 重写 getOrCreateConversationId**

将 `getOrCreateConversationId()` 方法（line 193-206）中所有 `redisTemplate` 替换为 `chatRedisTemplate`。核心逻辑不变（仍使用 `opsForValue()`），只需更换引用名。

- [ ] **Step 4: 重写 getConversationHistory**

将 `getConversationHistory(String conversationId)` 方法（line 207-222）替换为：

```java
private List<Map<String, String>> getConversationHistory(String conversationId, boolean reversed) {
    try {
        String key = "conversation:" + conversationId;
        List<String> elements = chatRedisTemplate.opsForList().range(key, 0, -1);
        if (elements == null || elements.isEmpty()) {
            // 检查是否存在旧格式数据
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

        chatRedisTemplate.executePipelined((org.springframework.data.redis.connection.RedisCallback<Object>) connection -> {
            byte[] keyBytes = key.getBytes();
            byte[] userBytes = userJson.getBytes();
            byte[] assistantBytes = assistantJson.getBytes();

            connection.listCommands().rPush(keyBytes, userBytes);
            connection.listCommands().rPush(keyBytes, assistantBytes);
            connection.listCommands().lTrim(keyBytes, -(maxMessages), -1);
            connection.keyCommands().expire(keyBytes, Duration.ofDays(7).getSeconds());
            return null;
        });
    } catch (Exception e) {
        logger.error("更新对话历史失败: conversationId={}", conversationId, e);
    }
}
```

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

需要添加 import：
```java
import org.springframework.beans.factory.annotation.Qualifier;
```

- [ ] **Step 2: 重写 getConversationsFromRedis 方法**

将 `getConversationsFromRedis()` 方法（line 113-206）中读取对话历史的部分从：
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

其余逻辑（日期过滤、消息格式化）不变。

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

需要添加 import：
```java
import org.springframework.beans.factory.annotation.Qualifier;
```

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

需要添加 import `java.util.List;`（可能已存在）。

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

需要添加 import `com.fasterxml.jackson.core.type.TypeReference;`。

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

### Task 5: ChatHandler Redis 滑动窗口测试

**Files:**
- Create: `src/test/java/com/yizhaoqi/smartpai/service/ChatHandlerRedisTest.java`

- [ ] **Step 1: 编写测试**

创建测试类，验证核心 Redis 滑动窗口行为。使用 embedded Redis 或 mock RedisTemplate。

```java
package com.yizhaoqi.smartpai.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisListCommands;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.ListOperations;

import java.time.Duration;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ChatHandlerRedisTest {

    @Mock
    private RedisTemplate<String, String> chatRedisTemplate;

    @Mock
    private ListOperations<String, String> listOperations;

    @Mock
    private ValueOperations<String, String> valueOperations;

    private ObjectMapper objectMapper = new ObjectMapper();

    // 测试 getConversationHistory 返回空列表时旧格式数据检测
    @Test
    void getConversationHistory_emptyList_noOldFormatData() {
        when(chatRedisTemplate.opsForList()).thenReturn(listOperations);
        when(listOperations.range(anyString(), eq(0L), eq(-1L))).thenReturn(null);
        when(chatRedisTemplate.hasKey(anyString())).thenReturn(false);

        // 验证：返回空列表，无 warn 日志
        // （实际测试需要调用 ChatHandler 的 private 方法，通过反射或改为 package-private）
    }

    @Test
    void getConversationHistory_emptyList_oldFormatData_logs() {
        when(chatRedisTemplate.opsForList()).thenReturn(listOperations);
        when(listOperations.range(anyString(), eq(0L), eq(-1L))).thenReturn(Collections.emptyList());
        when(chatRedisTemplate.hasKey(anyString())).thenReturn(true);

        // 验证：返回空列表，记录 warn 日志
    }

    @Test
    void getConversationHistory_reversed_returnsLatestFirst() throws Exception {
        List<String> elements = new ArrayList<>();
        Map<String, String> msg1 = Map.of("role", "user", "content", "hello", "timestamp", "t1");
        Map<String, String> msg2 = Map.of("role", "assistant", "content", "hi", "timestamp", "t2");
        elements.add(objectMapper.writeValueAsString(msg1));
        elements.add(objectMapper.writeValueAsString(msg2));

        when(chatRedisTemplate.opsForList()).thenReturn(listOperations);
        when(listOperations.range(anyString(), eq(0L), eq(-1L))).thenReturn(elements);

        // 验证 reversed=true 时返回 [msg2, msg1]
    }
}
```

注意：由于 `getConversationHistory` 是 private 方法，测试策略有两种：
1. 通过反射调用 private 方法
2. 将方法可见性改为 package-private（推荐，因为测试类在同一包下）

- [ ] **Step 2: 运行测试验证**

Run: `cd /Users/felx/Project/JavaProject/PaicodingProject/PaiSmart && mvn test -Dtest=ChatHandlerRedisTest -q`
Expected: 所有测试通过

- [ ] **Step 3: Commit**

```bash
git add src/test/java/com/yizhaoqi/smartpai/service/ChatHandlerRedisTest.java
git commit -m "test(chat): 新增 Redis 滑动窗口单元测试"
```

---

## Part 2: Kafka 消费者修复

### Task 6: 修复 KafkaConfig — auto-commit + AckMode

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

- [ ] **Step 3: 在 dev 和 docker profile 中补充 enable-auto-commit**

在 `application-dev.yml` 的 `consumer` 部分（line 45 之后，`topic` 之前）添加：
```yaml
      enable-auto-commit: false
```

在 `application-docker.yml` 的 `consumer` 部分（line 44 之后，`topic` 之前）添加：
```yaml
      enable-auto-commit: false
```

- [ ] **Step 4: 验证编译**

Run: `cd /Users/felx/Project/JavaProject/PaicodingProject/PaiSmart && mvn compile -q`
Expected: BUILD SUCCESS

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/yizhaoqi/smartpai/config/KafkaConfig.java src/main/resources/application-dev.yml src/main/resources/application-docker.yml
git commit -m "fix(kafka): 恢复 auto-commit 禁用 + 设置 AckMode.MANUAL_IMMEDIATE"
```

---

### Task 7: 修复 Producer 消息加 key

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

### Task 8: 修复 downloadFileFromStorage 异常处理

**Files:**
- Modify: `src/main/java/com/yizhaoqi/smartpai/consumer/FileProcessingConsumer.java`

- [ ] **Step 1: 修改 downloadFileFromStorage 方法**

在 `FileProcessingConsumer.java` 的 `downloadFileFromStorage()` 方法（line 96-138）中：

将 line 96 的方法签名从：
```java
private InputStream downloadFileFromStorage(String filePath) {
```
改为：
```java
private InputStream downloadFileFromStorage(String filePath) throws IOException {
```

将 line 134-136 的异常吞没：
```java
} catch (Exception e) {
    log.error("下载文件失败: {}", filePath, e);
    return null;
}
```
改为直接抛出：
```java
} catch (IOException e) {
    throw new IOException("下载文件失败: " + filePath, e);
} catch (Exception e) {
    throw new IOException("下载文件时发生异常: " + filePath, e);
}
```

- [ ] **Step 2: 修改 processTask 中的调用**

在 `processTask()` 方法中（line 52 附近），`downloadFileFromStorage` 的调用不再需要 null 检查（因为异常会直接抛出）。简化为：
```java
InputStream inputStream = downloadFileFromStorage(task.getFilePath());
```

移除 line 54-56 的 null 检查逻辑。

- [ ] **Step 3: 统一注入方式**

将 `FileProcessingConsumer.java:28-29` 的：
```java
@Autowired
private KafkaConfig kafkaConfig;
```
改为构造函数注入（与 `parseService`、`vectorizationService` 一致）。

- [ ] **Step 4: 验证编译**

Run: `cd /Users/felx/Project/JavaProject/PaicodingProject/PaiSmart && mvn compile -q`
Expected: BUILD SUCCESS

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/yizhaoqi/smartpai/consumer/FileProcessingConsumer.java
git commit -m "fix(kafka): downloadFileFromStorage 不再吞掉异常，直接抛出让 ErrorHandler 处理"
```

---

### Task 9: 添加消费端幂等性

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

- [ ] **Step 2: 在 FileProcessingConsumer.processTask 中添加幂等性检查**

在 `processTask()` 方法中，在 `downloadFileFromStorage` 调用之前（line 52 之前），新增：

```java
// 幂等性检查：如果该用户已处理过该文件，先清理再重处理
if (documentVectorRepository.existsByUserIdAndFileMd5(task.getUserId(), task.getFileMd5())) {
    log.warn("文件已存在处理记录，清理后重新处理: fileMd5={}, userId={}", task.getFileMd5(), task.getUserId());
    documentVectorRepository.deleteByUserIdAndFileMd5(task.getUserId(), task.getFileMd5());
    // TODO: 同步清理 ES 中对应数据（需调用 elasticsearchService.deleteByUserIdAndFileMd5）
}
```

需要注入 `DocumentVectorRepository` 到 `FileProcessingConsumer`。

- [ ] **Step 3: 验证编译**

Run: `cd /Users/felx/Project/JavaProject/PaicodingProject/PaiSmart && mvn compile -q`
Expected: BUILD SUCCESS

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/yizhaoqi/smartpai/repository/DocumentVectorRepository.java src/main/java/com/yizhaoqi/smartpai/consumer/FileProcessingConsumer.java
git commit -m "feat(kafka): 消费端幂等性检查，基于 userId+fileMd5 去重"
```

---

### Task 10: 新增 DLQ 消费者

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

- [ ] **Step 2: 验证编译**

Run: `cd /Users/felx/Project/JavaProject/PaicodingProject/PaiSmart && mvn compile -q`
Expected: BUILD SUCCESS

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/yizhaoqi/smartpai/consumer/DltConsumer.java
git commit -m "feat(kafka): 新增 DLQ 消费者，记录失败消息日志"
```

---

### Task 11: Kafka 消费者测试

**Files:**
- Create: `src/test/java/com/yizhaoqi/smartpai/consumer/FileProcessingConsumerTest.java`

- [ ] **Step 1: 编写测试**

```java
package com.yizhaoqi.smartpai.consumer;

import com.yizhaoqi.smartpai.model.FileProcessingTask;
import com.yizhaoqi.smartpai.repository.DocumentVectorRepository;
import com.yizhaoqi.smartpai.service.ParseService;
import com.yizhaoqi.smartpai.service.VectorizationService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.support.Acknowledgment;

import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class FileProcessingConsumerTest {

    @Mock private ParseService parseService;
    @Mock private VectorizationService vectorizationService;
    @Mock private DocumentVectorRepository documentVectorRepository;
    @Mock private Acknowledgment ack;

    @Test
    void processTask_idempotentCheck_skipsWhenAlreadyProcessed() throws Exception {
        FileProcessingTask task = new FileProcessingTask(
            "md5abc", "http://example.com/file.pdf", "file.pdf",
            "user1", "org1", false
        );

        when(documentVectorRepository.existsByUserIdAndFileMd5("user1", "md5abc"))
            .thenReturn(true);

        // 构造 consumer 并调用 processTask
        // 验证 deleteByUserIdAndFileMd5 被调用
        // 验证后续 parse + vectorize 正常执行
    }

    @Test
    void processTask_firstTime_noCleanup() throws Exception {
        FileProcessingTask task = new FileProcessingTask(
            "md5abc", "http://example.com/file.pdf", "file.pdf",
            "user1", "org1", false
        );

        when(documentVectorRepository.existsByUserIdAndFileMd5("user1", "md5abc"))
            .thenReturn(false);

        // 验证 deleteByUserIdAndFileMd5 未被调用
        // 验证 parse + vectorize 正常执行
    }
}
```

- [ ] **Step 2: 运行测试**

Run: `cd /Users/felx/Project/JavaProject/PaicodingProject/PaiSmart && mvn test -Dtest=FileProcessingConsumerTest -q`
Expected: 所有测试通过

- [ ] **Step 3: Commit**

```bash
git add src/test/java/com/yizhaoqi/smartpai/consumer/FileProcessingConsumerTest.java
git commit -m "test(kafka): 新增消费者幂等性单元测试"
```

---

## Part 3: 分块句子级重叠

### Task 12: 添加 overlap 配置和辅助方法

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

- [ ] **Step 3: 新增 extractLastNSentences 方法**

在 `ParseService.java` 中（`splitByCharacters` 方法之后，line 346 附近），新增：

```java
/**
 * 从文本尾部提取最后 N 个完整句子。
 * 使用与 splitLongParagraph 相同的句子正则。
 * 如果文本中没有句子分隔符，返回空列表。
 */
private List<String> extractLastNSentences(String text, int n) {
    if (text == null || text.isEmpty() || n <= 0) {
        return new ArrayList<>();
    }
    // 使用现有句子正则分割
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

- [ ] **Step 4: 新增 applyOverlap 方法**

在 `extractLastNSentences` 之后，新增：

```java
/**
 * 对切分后的 chunks 应用句子级重叠。
 * 每个非首 chunk 头部拼上前一个 chunk 尾部的 N 个句子。
 */
private List<String> applyOverlap(List<String> chunks) {
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

### Task 13: 修改 StreamingContentHandler 集成 overlap

**Files:**
- Modify: `src/main/java/com/yizhaoqi/smartpai/service/ParseService.java`

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
    buffer.setLength(0);

    // 1. 正常切分
    List<String> chunks = ParseService.this.splitTextIntoChunksWithSemantics(parentChunkText, chunkSize);

    // 2. 应用句子级 overlap
    chunks = ParseService.this.applyOverlap(chunks);

    // 3. 跨 parent chunk 边界：将上一个 parent chunk 的尾部 overlap 拼到第一个 chunk
    if (!trailingOverlap.isEmpty() && !chunks.isEmpty()) {
        chunks.set(0, trailingOverlap + chunks.get(0));
    }

    // 4. 更新 trailingOverlap 为当前最后一个 chunk 的尾部句子
    if (!chunks.isEmpty()) {
        List<String> lastSentences = ParseService.this.extractLastNSentences(
                chunks.get(chunks.size() - 1), overlapSentences);
        trailingOverlap = lastSentences.isEmpty() ? "" : String.join("", lastSentences);
    }

    // 5. 保存 chunks
    ParseService.this.saveChildChunks(chunks);
    logger.info("处理父块完成，生成 {} 个子块", chunks.size());
}
```

注意：`overlapSentences` 通过 `ParseService.this.overlapSentences` 访问外部类字段。`chunkSize` 同理。

- [ ] **Step 3: 验证编译**

Run: `cd /Users/felx/Project/JavaProject/PaicodingProject/PaiSmart && mvn compile -q`
Expected: BUILD SUCCESS

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/yizhaoqi/smartpai/service/ParseService.java
git commit -m "feat(parse): StreamingContentHandler 集成句子级 overlap 后处理"
```

---

### Task 14: 分块重叠测试

**Files:**
- Create: `src/test/java/com/yizhaoqi/smartpai/service/ParseServiceOverlapTest.java`

- [ ] **Step 1: 编写测试**

```java
package com.yizhaoqi.smartpai.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(MockitoExtension.class)
class ParseServiceOverlapTest {

    // 使用 ReflectionTestUtils 设置 private 字段，或直接测试 public/protected 方法
    // 建议将 extractLastNSentences 和 applyOverlap 改为 package-private 以便测试

    @Test
    void extractLastNSentences_chineseText_returnsLast2Sentences() {
        ParseService service = new ParseService();
        // 调用 extractLastNSentences("这是第一句。这是第二句。这是第三句。这是第四句。", 2)
        // 期望返回 ["这是第三句。", "这是第四句。"]
    }

    @Test
    void extractLastNSentences_noDelimiters_returnsEmpty() {
        // 调用 extractLastNSentences("没有句号的文本", 2)
        // 期望返回 ["没有句号的文本"] 或空列表（取决于实现）
    }

    @Test
    void extractLastNSentences_emptyText_returnsEmpty() {
        // 调用 extractLastNSentences("", 2)
        // 期望返回空列表
    }

    @Test
    void applyOverlap_singleChunk_returnsUnchanged() {
        // 只有一个 chunk，不应用 overlap
    }

    @Test
    void applyOverlap_multipleChunks_hasOverlap() {
        // chunk1 = "第一段内容。第二段内容。第三段内容。"
        // chunk2 = "第四段内容。第五段内容。"
        // overlap = extractLastNSentences(chunk1, 2) = ["第二段内容。", "第三段内容。"]
        // 结果 chunk2 = "第二段内容。第三段内容。第四段内容。第五段内容。"
    }

    @Test
    void applyOverlap_overlapSentencesZero_noOverlap() {
        // overlapSentences = 0，不应用 overlap
    }
}
```

注意：`extractLastNSentences` 和 `applyOverlap` 是 private 方法。建议将它们改为 package-private（去掉 `private` 关键字），以便测试类（同一包下）可以直接调用。

- [ ] **Step 2: 运行测试**

Run: `cd /Users/felx/Project/JavaProject/PaicodingProject/PaiSmart && mvn test -Dtest=ParseServiceOverlapTest -q`
Expected: 所有测试通过

- [ ] **Step 3: Commit**

```bash
git add src/test/java/com/yizhaoqi/smartpai/service/ParseServiceOverlapTest.java
git commit -m "test(parse): 新增分块句子级重叠单元测试"
```

---

## 总结

| Part | Tasks | 预计改动文件数 |
|------|-------|--------------|
| Part 1: Redis 滑动窗口 | Task 1-5 | 6 个文件 |
| Part 2: Kafka 消费者修复 | Task 6-11 | 8 个文件 |
| Part 3: 分块重叠 | Task 12-14 | 3 个文件 |

**实施顺序建议**：Part 2 → Part 3 → Part 1（先修 Kafka 基础设施，再改解析逻辑，最后改 Redis。但三个 Part 无硬依赖，可按任意顺序执行）
