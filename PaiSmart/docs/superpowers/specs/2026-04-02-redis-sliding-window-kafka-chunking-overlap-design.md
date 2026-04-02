# Redis 滑动窗口 + Kafka 消费者修复 + 分块重叠

日期：2026-04-02

## 概述

三个独立改进项：
1. Redis 聊天记录从 String+JSON 改为 List+LTRIM 滑动窗口
2. Kafka 消费者高优先级问题修复
3. 文件解析分块增加句子级重叠

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

### 改动点

**ChatHandler** — 3 个方法重写：

1. `getConversationHistory(String conversationId)`:
   - `LRANGE 0 -1` 获取所有元素
   - 逐元素反序列化为 `Map<String, String>`
   - 新增 `reversed` 参数控制是否反转顺序

2. `updateConversationHistory(String conversationId, String userMessage, String response)`:
   - Pipeline 执行：`RPUSH` 两条消息 + `LTRIM -maxMessages -1` + `EXPIRE 7d`
   - 不再需要先读取再写回

3. 配置提取：
   - 新增 `chat.history.max-messages` 配置项（默认 20），替代硬编码

**ConversationController** — 读取逻辑适配 List 结构，使用 `LRANGE`

**AdminController** — 同上，适配 List 读取

**RedisRepository** — 当前未被使用，可暂不改动

### 配置

```yaml
chat:
  history:
    max-messages: 20
```

### 注意事项

- 消息格式不变：每个元素仍是 `{"role":"...", "content":"...", "timestamp":"..."}`
- TTL 继续用 `expire(key, 7 days)`，在 pipeline 中一并设置
- `RedisTemplate` 的 value serializer 需要确认兼容 String 元素的 List 操作（当前用 `GenericJackson2JsonRedisSerializer`，改为 `StringRedisSerializer` 或使用 `StringRedisTemplate`）

---

## Part 2：Kafka 消费者修复

### 第一批（高优先级）

#### 修复 1：恢复 auto-commit 禁用

**问题**：`KafkaConfig.consumerFactory()` 中 `ENABLE_AUTO_COMMIT_CONFIG=false` 被注释掉，programmatic factory 未继承 YAML 中的配置，自动提交可能实际生效。

**改动**：
- `KafkaConfig.consumerFactory()` 取消注释 `config.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false)`
- 在 `application-dev.yml` 和 `application-docker.yml` 中统一添加 `enable-auto-commit: false`

#### 修复 2：Producer 消息加 key

**问题**：`UploadController.mergeFile()` 中 `kt.send(topic, task)` 未指定 key，消息随机分配到不同 partition，同一文件的消息无法保证有序。

**改动**：
- `kt.send(topic, task)` → `kt.send(topic, task.getFileMd5(), task)`
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
- `KafkaConfig` 中添加 `factory.getContainerProperties().setAckMode(AckMode.MANUAL_IMMEDIATE)`
- 确保 `ack.acknowledge()` 立即提交 offset

#### 修复 5：消费端幂等性

**问题**：消息重投递会导致重复解析和向量化。

**改动**：
- `DocumentVectorRepository` 新增 `boolean existsByUserIdAndFileMd5(String userId, String fileMd5)`
- 消费开始前查询：如果已存在，跳过解析和向量化，直接 ack
- 唯一标识为 `userId + fileMd5`（不同用户上传相同文件视为不同文档）

#### 修复 6：DLQ 消费者

**问题**：DLQ topic 有配置但无消费者，失败消息只进不出。

**改动**：
- 新增 `DltConsumer` 类，`@KafkaListener` 监听 `file-processing-dlt` topic
- 记录失败消息到日志（包含 fileMd5、userId、错误信息）
- 可选：更新文件状态为 FAILED、接入告警

### 暂不处理

- **事务原子性**（JPA `@Transactional` + Kafka `executeInTransaction` 非原子）：改动较大（需引入 local message table 模式），单独规划
- **trusted.packages: "\*"**：安全风险但非紧急，可随整体安全加固处理

---

## Part 3：分块句子级重叠

### 现状

- `ParseService` 使用两层分割：Tika 流式解析 → 1MB parent chunk 缓冲 → 四层递归语义切分（段落→句子→词→字符）
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

#### 切分后处理 — overlap 拼接

`saveChildChunks()` 中，切分完成后遍历 chunks：

```
chunks = splitTextIntoChunksWithSemantics(text, chunkSize)  // 不变

for i from 1 to chunks.size()-1:
    overlapSentences = extractLastNSentences(chunks[i-1], overlapCount)
    chunks[i] = join(overlapSentences) + chunks[i]

// chunks[0] 无前缀，保持原样
```

`extractLastNSentences` 使用现有句子正则 `(?<=[。！？；])|(?<=[.!?;])\s+` 提取尾部 N 个完整句子。

#### 跨 parent chunk 边界处理

`StreamingContentHandler` 新增成员变量 `String trailingOverlap = ""`：

```
processParentChunk():
    1. 正常切分（trailingOverlap 不参与切分输入）
    2. 对 chunks 做 overlap 拼接（后处理）
    3. 如果 trailingOverlap 非空，拼接到第一个 chunk 头部
    4. 更新 trailingOverlap = 最后一个 chunk 的尾部 2 句
    5. saveChildChunks()
```

#### endDocument 处理

`endDocument()` 处理剩余 buffer 时，同样经过上述流程，最后一块的 `trailingOverlap` 丢弃（无后续 chunk 需要）。

### 配置

```yaml
file:
  parsing:
    chunk-size: 512
    overlap-sentences: 2    # 新增：每个 chunk 头部重叠的句子数
```

### 存储影响

- Chunk 数量略增（overlap 部分导致部分 chunk 体积增大，但不产生额外 chunk）
- 实际上 chunk 总数不变，只是每个非首 chunk 的 `textContent` 多了 overlap 前缀
- MySQL `document_vectors` 表和 ES 索引的文档数不变，单条文档体积略增

### 不改动的部分

- `DocumentVector` 实体不变
- ES mapping 不变
- 向量化流程不变
- 四层递归切分算法不变
