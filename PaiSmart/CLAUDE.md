# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

PaiSmart (派聪明) is an enterprise-grade RAG (Retrieval-Augmented Generation) knowledge management system. Users upload documents, which are parsed, chunked, vectorized, and indexed into Elasticsearch. Users then chat via WebSocket, and the system retrieves relevant chunks to generate AI-powered responses grounded in their documents.

## Common Commands

### Backend (run from project root)
```bash
mvn spring-boot:run                              # Start with default profile
mvn spring-boot:run -Dspring-boot.run.profiles=dev # Start with dev profile
mvn clean package                                  # Build JAR
mvn test                                           # Run all tests
mvn test -Dtest=UserServiceTest                    # Run single test class
mvn test -Dtest=UserServiceTest#testMethodName     # Run single test method
mvn clean verify                                   # Build with integration tests
```

### Frontend (run from frontend/)
```bash
pnpm install    # Install dependencies
pnpm dev        # Start dev server
pnpm build      # Production build
pnpm typecheck  # TypeScript type checking
pnpm lint       # ESLint
```

### Infrastructure
```bash
cd docs && docker-compose up -d   # Start MySQL, Redis, ES, Kafka, MinIO
```

## Architecture

### Document Pipeline (ingestion → indexed)

Four stages, each in a separate service:

1. **Ingestion**: `UploadController` → `UploadService` (chunked upload with MD5 dedup, stores to MinIO) → publishes to Kafka topic `file-processing-topic1`
2. **Parsing**: `FileProcessingConsumer` (Kafka) → `ParseService` → Apache Tika extraction → semantic chunking (paragraph-aware, HanLP Chinese segmentation for long sentences, 512-char chunks) → stores `DocumentVector` entities in MySQL
3. **Vectorization**: `VectorizationService` → DashScope embedding-3 API (batch 10 chunks/request, 2048-dim vectors) → indexes into Elasticsearch
4. **Retrieval**: `HybridSearchService` → query embedding → KNN search (top 150 candidates) → BM25 rescoring → multi-tenant permission filter → ranked results

### Agentic RAG Chat Pipeline (the core of the system)

`ChatHandler` now routes every user message through a three-way classification before generation:

```
User Message → QueryRouter.classify()
  ├── [direct]  → lightweight system prompt → GLM streaming (chitchat, no retrieval)
  ├── [rag]     → single HybridSearchService.searchWithPermission() → GLM streaming
  └── [agent]   → AgentOrchestrator.executeAsync()
                    → PlannerAgent.plan() (1-4 sub-queries)
                    → parallel KnowledgeSearchTool per sub-query
                    → dedup + merge results
                    → EvaluateResultsTool (up to max-retry times)
                    → if insufficient: QueryRewriteTool → supplemental search
                    → failure/timeout → fallback to rag path
                  → GLM streaming
```

The `agent/` package contains:
- `QueryRouter`: LLM-driven three-way classification (direct/rag/agent)
- `AgentOrchestrator`: Semaphore-limited async execution (20 concurrent, 15s timeout)
- `PlannerAgent`: Decomposes complex queries into sub-queries for parallel retrieval
- `AgentContext`: ThreadLocal holder for userId, used by tools for permission filtering
- `AgentResult`: Wraps merged search results, provides `buildContext()` for prompt construction
- `tool/KnowledgeSearchTool`: Wraps `HybridSearchService`, reads userId from `AgentContext`
- `tool/EvaluateResultsTool`: LLM evaluator checking result sufficiency
- `tool/QueryRewriteTool`: LLM rewriter generating improved queries from identified gaps

### Async Processing (Kafka)

- **Producer**: `UploadService` publishes after file merge in MinIO
- **Consumer**: `FileProcessingConsumer` downloads from MinIO, triggers parse + vectorize
- **DLQ**: Failed messages go to `file-processing-dlt` topic
- **Consumer group**: `file-processing-group`

### Multi-Tenant Access Control

Documents have three visibility levels: owner's own, public, and organization-scoped. Organization tags support hierarchical inheritance (e.g., "company/team/subteam"). Permission filtering is applied at both the search query level (ES filter) and the document access level (repository queries). `RedisRepository` caches org tag resolution.

### Security

- JWT tokens via `JwtAuthenticationFilter`, validated on every request
- `OrgTagAuthorizationFilter` enforces organization-level data isolation
- WebSocket auth: JWT passed in URL path (`ws://host/chat/{jwtToken}`)
- Roles: USER, ADMIN (method-level via `@PreAuthorize`)
- User identity injected via `@RequestAttribute("userId")` in controllers

### WebSocket Chat

- `ChatWebSocketHandler` manages per-user sessions
- Client connects to `/chat/{jwtToken}`, sends JSON messages
- Responses stream as `{ chunk: "text" }` frames, completed with `{ type: "completion" }`
- Stop generation via internal command tokens

### Thread Pool Architecture

`ThreadPoolConfig` defines four isolated pools (all `CallerRunsPolicy` backpressure):

| Bean | Prefix | Core/Max/Queue | Purpose |
|------|--------|---------------|---------|
| `chatExecutor` | `chat-` | 8/32/200 | WebSocket chat main: history + search + LLM streaming |
| `agentPool` | `agent-` | 4/16/50 | Agent orchestration: routing, planning, aggregation |
| `llmCallPool` | `llm-` | 8/32/200 | All LLM API calls (GLM/DeepSeek HTTP requests) |
| `toolPool` | `tool-` | 8/16/100 | Agent tool execution (knowledge search, evaluation, rewrite) |

### Spring AI Integration

Spring AI 1.0.0 (`spring-ai-starter-model-openai`) provides four `ChatClient` beans in `SpringAiConfig`, all using GLM-4.7-flashx via OpenAI-compatible API:

- `routerChatClient` — query classification (temp 0.1)
- `plannerChatClient` — sub-query decomposition (temp 0.1)
- `evaluatorChatClient` — result quality evaluation (temp 0.1)
- `rewriterChatClient` — query rewriting (temp 0.3)

### Key Services

| Service | Role |
|---------|------|
| `ChatHandler` | Routes through QueryRouter, orchestrates RAG/agent pipeline, builds prompt, streams LLM response |
| `ParseService` | Document parsing with streaming Tika extraction and semantic chunking (monitors JVM memory at 80% threshold) |
| `HybridSearchService` | Combines KNN vector search + BM25 keyword rescoring with permission filtering |
| `VectorizationService` | Batch embedding generation and ES indexing |
| `UploadService` | Chunked file upload with MD5 dedup, MinIO storage, Kafka publish |
| `DocumentService` | Document CRUD, coordinates deletion across MinIO + MySQL + ES |

### AI Clients (`client/`)

- `GLMClient`: Primary LLM (DeepSeek/GLM), streaming via WebClient/WebFlux `Flux<String>`
- `DeepSeekClient`: Alternate LLM client (structurally similar, not used by ChatHandler)
- `EmbeddingClient`: DashScope embedding-3 API for text vectorization
- Generation params configured in `application.yml` under `ai.generation` (temperature: 0.3, max-tokens: 2000)

### Entity/Model Split

- `model/`: JPA entities mapped to MySQL (`FileUpload`, `DocumentVector`, `User`, `Conversation`, `OrganizationTag`)
- `entity/`: Elasticsearch document models (`EsDocument`) and search result DTOs

### Frontend

Vue 3 + TypeScript + Naive UI + Pinia + UnoCSS. pnpm workspace monorepo with shared packages under `frontend/packages/`. WebSocket chat is in the `/chat` view. API calls go through `service/` layer with axios. State managed in Pinia stores under `store/`. Environment files: `.env` (base), `.env.test` (localhost:8081), `.env.prod` (nginx proxy).

## Configuration Profiles

- `application.yml`: Base config (DB, Redis, Kafka, MinIO, ES, AI API keys, JWT secret)
- `application-dev.yml`: Local development overrides
- `application-docker.yml`: Docker deployment overrides

AI prompt engineering is configured under `ai.prompt` in YAML:
- `ai.prompt.rules`: Main system prompt for RAG/agent routes (includes `<<REF>>`/`<<END>>` reference delimiters, no-result fallback text)
- `ai.prompt.direct-rules`: Lightweight system prompt for the direct (chitchat) route
- Agent configuration under `agent:` (timeout, semaphore limits, planner max-sub-queries, evaluator max-retry)

### Elasticsearch Index

Index `knowledge_base` is auto-created at startup via `EsIndexInitializer` using mapping from `src/main/resources/es-mappings/knowledge_base.json`. Uses `ik_max_word`/`ik_smart` analyzers (Chinese segmentation), 2048-dim `dense_vector` with cosine similarity, and keyword fields for permission filtering (`userId`, `orgTag`, `isPublic`).

### Pre-commit Hooks

Commits from the frontend directory require passing `pnpm typecheck` and `pnpm lint` (configured via `simple-git-hooks` in root `package.json`).

## External Dependencies

MySQL 8.0 | Redis 7.0.11 | Elasticsearch 8.10.0 | Kafka 3.2.1 | MinIO 8.5.12 | DashScope Embedding API | DeepSeek/GLM API

## Tech Stack

Spring Boot 3.4.2 (Java 17) | Spring AI 1.0.0 | Spring Data JPA | Spring Security + JWT | Spring WebFlux | Apache Tika 2.9.1 | HanLP | Lombok | Vue 3.5 | Vite 6.3 | Naive UI | Pinia 3.0 | UnoCSS | pnpm
