# Redis 滑动窗口 + Kafka 消费者修复 + 分块重叠

日期：2026-04-02

## 概述

三个独立改进项，可独立部署：
1. Redis 聊天记录从 String+JSON 改为 List+LTRIM 滑动窗口
2. Kafka 消费者高优先级问题修复
3. 文件解析分块增加句子级重叠

**部署顺序**：三个 Part 无依赖关系，可按任意顺序实施和部署。Part 3（分块重叠）仅影响部署后新摄取的文档，已有文档的 chunk 形状不变。

---

## Part 1：Redis 聊天记录滑动窗口

### 现状

- `ChatHandler` 用 `opsForValue()` 将整个消息列表序列化为 JSON String 存入 Redis
- Key: `conversation:{conversationId}`, TTL 7 天
- 每次写入：读取 → 反序列化 → 追加 → 截断到 20 条 → 序列化 → 写回
- 硬编码 20 条上限

### 目标

改为 Redis List + RPUSH/LTRIM 实现滑动窗口，按消息数量控制窗口大小。

### 数据结构变更

| 旧 | 新 |
|----|-----|
| `String` key，存完整 JSON 数组 | `List` key，每条消息一个 JSON 字符串元素 |
| `opsForValue().get/set()` | `opsForList().rightPush()` + `opsForList().trim()` |
| 应用层截断到 20 条 | Redis `LTRIM -N -1` 原子截断 |

### List 内部顺序

使用 `RPUSH` 追加到尾部，`LTRIM -maxMessages -1` 保留尾部 N 条。

- List 天然时间正序：`[最旧, ..., 最新]`
- **前端**：`LRANGE 0 -1` 直接返回（时间正序）
- **LLM 上下文**：`LRANGE 0 -1` 后 `Collections.reverse()`（最新优先）

### RedisTemplate 方案

当前 `RedisConfig` 注册了 `RedisTemplate<String, Object>`（value serializer 为 `GenericJackson2JsonRedisSerializer`），而 `ChatHandler`/`ConversationController`/`AdminController` 注入的是 `RedisTemplate<String, String>`（通过类型擦除生效）。但 `GenericJackson2JsonRedisSerializer` 会在 List 操作时为每个元素包装 JSON 类型元数据。

**方案**：在 `RedisConfig` 中新增一个 `@Bean("chatRedisTemplate")`，使用 `StringRedisSerializer` 作为 value serializer，专门用于聊天相关的 List 操作。`ChatHandler`、`ConversationController`、`AdminController` 改为注入此 bean（`@Qualifier("chatRedisTemplate")`）。Spring Boot 自动配置的 `StringRedisTemplate` 也可直接使用，但为了明确语义，选择自定义 bean。

### 改动点

**RedisConfig** — 新增 `chatRedisTemplate` bean：
```java
@Bean("chatRedisTemplate")
public RedisTemplate<String, String> chatRedisTemplate(RedisConnectionFactory factory) {
    RedisTemplate<String, String> template = new RedisTemplate<>();
    template.setConnectionFactory(factory);
    template.setKeySerializer(new StringRedisSerializer());
    template.setValueSerializer(new StringRedisSerializer());
    return template;
}
```

**ChatHandler** — 3 个方法重写：

1. `getConversationHistory(String conversationId, boolean reversed)`:
   - `LRANGE 0 -1` 获取所有元素
   - 逐元素反序列化为 `Map<String, String>`
   - 如果 `reversed=true`，`Collections.reverse()` 后返回
   - **调用点**：`processMessage()` 中调用时传 `reversed=true`（LLM 需要最新优先），`updateConversationHistory()` 内部不再读取历史（不再需要）

2. `updateConversationHistory(String conversationId, String userMessage, String response)`:
   - Pipeline 执行：`RPUSH` 两条消息 + `LTRIM -maxMessages -1` + `EXPIRE 7d`
   - 不再需要先读取再写回

3. 配置提取：
   - 新增 `@Value("${chat.history.max-messages:20}") private int maxMessages`，替代硬编码

**ConversationController** — `getConversationsFromRedis()` 方法：
- `opsForValue().get(key)` 改为 `opsForList().range(key, 0, -1)`
- 返回 `List<String>` 元素，逐个反序列化

**AdminController** — `getAllConversations()` 和 `processRedisConversation()` 方法：
- `opsForValue().get(key)` 改为 `opsForList().range(key, 0, -1)`
- `processRedisConversation` 签名从 `String json` 改为 `List<String> elements`

### 数据迁移

旧数据（String 格式）会在 7 天 TTL 到期后自然消失。过渡期间：
- 部署后新产生的消息使用 List 格式写入
- 旧的 String key 可能仍存在但不会被新代码读取（因为 key pattern 相同，但读取方式从 `opsForValue` 变为 `opsForList`）
- **处理策略**：在 `getConversationHistory()` 中，如果 `LRANGE` 返回空但 key 存在（通过 `hasKey` 检查），说明是旧格式数据，记录一条 warn 日志提示"存在旧格式对话数据，将在 TTL 到期后自动清理"，并返回空列表
- 不编写迁移脚本，接受过渡期内旧对话历史不可读（用户重新发消息后会创建新的 List 格式 key）

### 配置

```yaml
chat:
  history:
    max-messages: 20
```

### 测试要点

- 边界测试：0 条、1 条、20 条、21 条消息的写入和截断
- 验证 LTRIM 在 21 条时正确截断为 20 条
- 验证前端返回时间正序，LLM 上下文返回最新优先

---

## Part 2：Kafka 消费者修复

### 第一批（高优先级）

#### 修复 1：恢复 auto-commit 禁用

**问题**：`KafkaConfig.consumerFactory()` 中 `ENABLE_AUTO_COMMIT_CONFIG=false` 被注释掉，programmatic factory 未继承 YAML 中的配置，自动提交可能实际生效。

**改动**：
- `KafkaConfig.consumerFactory()` 取消注释 `config.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false)` — 这是关键改动，因为 programmatic factory 不会自动继承 YAML 配置
- 在 `application-dev.yml` 和 `application-docker.yml` 中也显式添加 `enable-auto-commit: false`（虽然它们从基础 profile 继承了该值，但显式声明可防止未来误删基础配置）
- 修复 1 与修复 4（AckMode）配合生效：禁用自动提交 + 设置 MANUAL_IMMEDIATE 确保手动 ack 立即提交 offset

#### 修复 2：Producer 消息加 key

**问题**：`UploadController.mergeFile()` 中 `kt.send(topic, task)` 未指定 key，消息随机分配到不同 partition，同一文件的消息无法保证有序。

**改动**：
- `kt.send(kafkaConfig.getFileProcessingTopic(), task)` → `kt.send(kafkaConfig.getFileProcessingTopic(), task.getFileMd5(), task)`
- `FileProcessingTask` 已有 `getFileMd5()` 方法（确认存在于模型类中）
- 以 `fileMd5` 为 key，同一文件路由到同一 partition

#### 修复 3：downloadFileFromStorage 异常处理

**问题**：`FileProcessingConsumer.downloadFileFromStorage()` 吞掉异常返回 null，丢失根因。

**改动**：
- 方法签名改为 `throws IOException`
- 移除 try-catch 或 catch 后包装重新抛出：`throw new IOException("下载文件失败: " + filePath, e)`
- 让 `DefaultErrorHandler` 统一处理 retry/DLQ

### 第二批（建议修复）

#### 修复 4：AckMode 显式设置

**改动**：
- `KafkaConfig.kafkaListenerContainerFactory()` 中添加 `factory.getContainerProperties().setAckMode(AckMode.MANUAL_IMMEDIATE)`
- 确保 `ack.acknowledge()` 立即同步提交 offset
- 与修复 1 配合：修复 1 禁用自动提交，修复 4 确保手动提交立即生效

#### 修复 5：消费端幂等性

**问题**：消息重投递会导致重复解析和向量化。

**改动**：
- `DocumentVectorRepository` 新增 `boolean existsByUserIdAndFileMd5(String userId, String fileMd5)`
- 消费开始前查询：如果已存在，跳过解析和向量化，直接 ack
- 唯一标识为 `userId + fileMd5`（不同用户上传相同文件视为不同文档）
- **幂等性语义**：`exists` 检查的是"是否有任何 chunk 存在"。如果文件之前被部分处理（部分 chunk 已保存），则 `exists` 返回 true 会跳过剩余 chunk。因此需要在重处理前先清理已有数据：
  - 检查 `existsByUserIdAndFileMd5`
  - 如果存在，先 `deleteByUserIdAndFileMd5`（需新增此方法），清理 ES 中对应数据，然后重新完整处理
  - 这保证了重新处理的结果是完整的，不会出现部分 chunk 缺失

#### 修复 6：DLQ 消费者

**问题**：DLQ topic 有配置但无消费者，失败消息只进不出。

**改动**：
- 新增 `DltConsumer` 类，`@KafkaListener` 监听 `file-processing-dlt` topic
- 记录失败消息到日志（包含 fileMd5、userId、错误信息）
- 可选：更新文件状态为 FAILED、接入告警

### 暂不处理

- **事务原子性**（JPA `@Transactional` + Kafka `executeInTransaction` 非原子）：改动较大（需引入 local message table 模式），单独规划
- **trusted.packages: "\*"**：安全风险但非紧急，可随整体安全加固处理

### 测试要点

- 修复 1+4：验证 `consumerFactory` 配置中 `ENABLE_AUTO_COMMIT_CONFIG` 为 false，`AckMode` 为 MANUAL_IMMEDIATE
- 修复 2：验证 producer 发送消息的 key 为 fileMd5
- 修复 5：验证重复消息被正确跳过（先清理再重处理的完整流程）

---

## Part 3：分块句子级重叠

### 现状

- `ParseService` 使用两层分割：Tika 流式解析 → 1MB parent chunk 缓冲 → 四层递归语义切分（段落→句子→词→字符）
- `StreamingContentHandler` 是 `ParseService` 的**私有内部类**（line 122），通过 `ParseService.this` 访问外部类方法
- Chunk size 512 字符，各层贪婪累积直到下一单元会超限
- 相邻 chunk 之间无任何重叠

### 目标

在现有切分逻辑基础上，增加句子级别的重叠，使相邻 chunk 之间有语义过渡。

### 设计原则

- 切分逻辑（四层递归）零改动
- Overlap 作为纯后处理步骤，在切分完成后执行
- 允许 chunk 略大于 512（overlap 部分不计入上限），不裁剪
- 跨 parent chunk 边界的 overlap 也需处理

### 核心逻辑

#### 配置注入

`ParseService` 新增：
```java
@Value("${file.parsing.overlap-sentences:2}")
private int overlapSentences;
```

`StreamingContentHandler` 内部类通过 `ParseService.this.overlapSentences` 访问该值。

#### 切分后处理 — overlap 拼接

新增方法 `applyOverlap(List<String> chunks, int overlapCount)`：

```
chunks = splitTextIntoChunksWithSemantics(text, chunkSize)  // 不变

for i from 1 to chunks.size()-1:
    overlapSentences = extractLastNSentences(chunks[i-1], overlapCount)
    chunks[i] = join(overlapSentences) + chunks[i]

// chunks[0] 无前缀，保持原样
```

`extractLastNSentences` 使用现有句子正则 `(?<=[。！？；])|(?<=[.!?;])\s+` 提取尾部 N 个完整句子。

**边界情况**：如果 chunk 尾部没有句子分隔符（如以省略号或未终止段落结尾），`extractLastNSentences` 返回实际能提取到的句子（可能少于 N 个，甚至为 0）。此时 overlap 前缀为空字符串，不影响 chunk。不强制补齐。

#### 跨 parent chunk 边界处理

`StreamingContentHandler` 内部类新增成员变量 `private String trailingOverlap = ""`：

```
processParentChunk():
    1. 正常切分 → chunks = splitTextIntoChunksWithSemantics(text, chunkSize)
    2. 对 chunks 调用 applyOverlap(chunks, overlapSentences)
    3. 如果 trailingOverlap 非空，拼接到 chunks[0] 头部：
       chunks[0] = trailingOverlap + chunks[0]
    4. 更新 trailingOverlap = extractLastNSentences(chunks[last], overlapSentences)
    5. 调用 saveChildChunks(chunks)（传入已修改的 chunks）
```

#### endDocument 处理

`endDocument()` 内部只是调用 `processParentChunk()` 处理剩余 buffer，无需特殊逻辑。最后一块处理后 `trailingOverlap` 会被设置但无后续 chunk 使用，自然丢弃。

### 配置

```yaml
file:
  parsing:
    chunk-size: 512
    overlap-sentences: 2    # 新增：每个 chunk 头部重叠的句子数
```

`ParseService` 通过 `@Value("${file.parsing.overlap-sentences:2}")` 注入。

### 存储影响

- Chunk 总数不变（不产生额外 chunk），只是每个非首 chunk 的 `textContent` 多了 overlap 前缀
- MySQL `document_vectors` 表和 ES 索引的文档数不变，单条文档体积略增
- 仅影响部署后新摄取的文档，已有文档不变

### 不改动的部分

- `DocumentVector` 实体不变
- ES mapping 不变
- 向量化流程不变
- 四层递归切分算法不变

### 测试要点

- 单 chunk 文档（不足 512 字符）：无 overlap 产生，行为不变
- 多 chunk 文档：验证相邻 chunk 之间有正确的句子级重叠
- 跨 parent chunk 边界：验证最后一个 chunk 的尾部句子出现在下一个 parent chunk 的第一个 chunk 头部
- 无句子分隔符的文本：验证 overlap 为空，不影响正常分块
