# Router Agent 使用指南

## 📋 概述

Router Agent 是一个智能路由系统，它能够：

1. ✅ **意图分析** - 使用大模型分析用户查询意图
2. ✅ **智能路由** - 根据 Agent Card 描述选择合适的 Agent
3. ✅ **查询拆分** - 将复杂查询拆分成多个子查询
4. ✅ **并行调度** - 同时调用多个 Agent 执行任务
5. ✅ **响应聚合** - 使用大模型整合所有 Agent 的响应
6. ✅ **统一接口** - 提供简洁的 REST API

## 🏗️ 架构图

```
用户请求
    ↓
RouterController
    ↓
RouterService
    ├─→ IntentAnalyzer (大模型分析意图)
    │       ↓
    │   选择合适的 Agent
    │       ↓
    ├─→ AgentOrchestrator (并行调度)
    │       ├─→ Agent 1 (翻译)
    │       ├─→ Agent 2 (旅行推荐)
    │       └─→ Agent 3 (天气查询)
    │           ↓
    │   收集所有响应
    │       ↓
    └─→ LLMService (聚合响应)
            ↓
    统一的最终响应
```

## 🚀 快速开始

### 1. 启动 Router Agent

```bash
cd /Users/kuai.yu/IdeaProjects/TravelAssistant/custom_java_impl/client
mvn spring-boot:run
```

Router Agent 将在 `http://localhost:9000` 启动

### 2. 发送请求

**方式 1：使用 JSON 请求**

```bash
curl -X POST http://localhost:9000/api/router/query \
  -H "Content-Type: application/json" \
  -d '{
    "userId": "user123",
    "query": "我想去巴黎旅游，帮我翻译景点名称并推荐酒店",
    "contextId": "conv-456"
  }'
```

**方式 2：使用简单的 GET 请求**

```bash
curl "http://localhost:9000/api/router/query?q=推荐一些北京的景点并查询天气"
```

### 3. 响应示例

```json
{
  "requestId": "req-12345",
  "finalResponse": "根据您的需求，我为您整合了以下信息：\n\n【翻译】埃菲尔铁塔 (Tour Eiffel)...\n\n【酒店推荐】巴黎埃菲尔铁塔附近有以下推荐酒店...",
  "agentResults": [
    {
      "agentName": "translator",
      "query": "翻译埃菲尔铁塔",
      "success": true,
      "responseText": "Eiffel Tower",
      "executionTimeMs": 1200
    },
    {
      "agentName": "hotel-booking",
      "query": "巴黎埃菲尔铁塔附近的酒店",
      "success": true,
      "responseText": "推荐以下酒店...",
      "executionTimeMs": 2100
    }
  ],
  "totalExecutionTimeMs": 2500,
  "metadata": {
    "intents": ["translation", "hotel"],
    "selectedAgents": ["translator", "hotel-booking"],
    "reasoning": "用户需要翻译和酒店推荐服务",
    "confidence": 0.92
  }
}
```

## 📊 工作流程

### 详细步骤

1. **接收请求** (`RouterController`)
   - 用户查询：「我想去巴黎旅游，帮我翻译景点并推荐酒店」

2. **意图分析** (`IntentAnalyzer`)
   - 使用大模型分析用户意图
   - 识别需要的技能：`[translation, travel, hotel]`
   - 选择 Agent：`[translator, travel-recommender, hotel-booking]`

3. **查询拆分**
   - translator: "翻译巴黎著名景点名称"
   - travel-recommender: "推荐巴黎必去景点"
   - hotel-booking: "巴黎埃菲尔铁塔附近的酒店"

4. **并行执行** (`AgentOrchestrator`)
   ```
   ┌──────────────┐
   │ Translator   │ ──→ "Eiffel Tower, Louvre..."
   ├──────────────┤
   │ Travel Agent │ ──→ "推荐埃菲尔铁塔、卢浮宫..."
   ├──────────────┤
   │ Hotel Agent  │ ──→ "推荐酒店列表..."
   └──────────────┘
   ```

5. **响应聚合** (`LLMService`)
   - 将所有 Agent 响应提供给大模型
   - 大模型整合成连贯的最终响应

6. **返回结果**
   - 包含最终响应、每个 Agent 的详细结果、执行时间等

## 🔧 配置说明

### application.yml

```yaml
server:
  port: 9000  # Router Agent 端口

router:
  agents:
    config: agents-config.yaml  # Agent 配置文件
```

### agents-config.yaml

确保配置文件中包含所有需要的 Agent：

```yaml
agents:
  - name: translator
    url: http://localhost:8080
    skills: [translation, language]
    enabled: true
    
  - name: travel-recommender
    url: http://localhost:8081
    skills: [travel, recommendation]
    enabled: true
    
  - name: hotel-booking
    url: http://localhost:8083
    skills: [hotel, booking]
    enabled: true
```

## 💡 使用场景

### 场景 1: 简单单一意图

**用户**：「帮我翻译 Hello World」

**流程**：
- 分析：只需要翻译服务
- 选择：translator
- 执行：单个 Agent
- 返回：直接返回翻译结果

### 场景 2: 复杂多意图

**用户**：「我要去东京旅游，帮我翻译景点名称、推荐酒店和查询天气」

**流程**：
- 分析：需要翻译、旅游、酒店、天气服务
- 选择：translator, travel-recommender, hotel-booking, weather
- 拆分查询：
  - translator: "翻译东京著名景点"
  - travel-recommender: "推荐东京旅游景点"
  - hotel-booking: "东京酒店推荐"
  - weather: "东京天气预报"
- 并行执行：4个 Agent 同时工作
- 聚合：整合成一个完整的旅游计划

### 场景 3: 上下文对话

**第一轮**：
- 用户：「推荐一些巴黎的景点」
- 系统：「推荐埃菲尔铁塔、卢浮宫...」

**第二轮**（相同 contextId）：
- 用户：「那附近有什么好酒店吗？」
- 系统：（基于上下文）「在埃菲尔铁塔附近推荐以下酒店...」

## 🎯 集成大模型

### 替换 SimpleLLMService

当前使用的是 `SimpleLLMService`（简单实现），生产环境应替换为真实的大模型服务：

#### 选项 1: Spring AI

```java
@Bean
public LLMService llmService(ChatModel chatModel) {
    return new SpringAILLMService(chatModel);
}
```

#### 选项 2: OpenAI

```java
@Bean
public LLMService llmService() {
    return new OpenAILLMService(apiKey);
}
```

#### 选项 3: 阿里云通义千问

```java
@Bean
public LLMService llmService() {
    return new QwenLLMService(apiKey);
}
```

### 实现示例

```java
public class SpringAILLMService implements LLMService {
    
    private final ChatClient chatClient;
    
    @Override
    public String analyze(String prompt) {
        return chatClient.prompt(prompt)
                .call()
                .content();
    }
    
    @Override
    public String aggregate(String userQuery, Map<String, String> agentResponses) {
        String aggregationPrompt = buildAggregationPrompt(userQuery, agentResponses);
        return chatClient.prompt(aggregationPrompt)
                .call()
                .content();
    }
}
```

## 📈 性能优化

### 1. 并行执行超时设置

当前默认 60秒，可在 `AgentOrchestrator` 中调整：

```java
allOf.get(60, TimeUnit.SECONDS);  // 调整超时时间
```

### 2. 线程池配置

```java
// 在 AgentOrchestrator 构造函数中
this.executorService = Executors.newFixedThreadPool(10);  // 固定大小线程池
```

### 3. Agent 健康检查

Agent Manager 会自动进行健康检查，不健康的 Agent 不会被选择

## 🔍 监控和日志

### 查看日志

```bash
tail -f logs/a2a-client.log
```

### 关键日志

```
2025-10-26 20:00:00 [main] INFO  RouterService - Routing request: req-123 - 我想去巴黎旅游
2025-10-26 20:00:00 [main] INFO  IntentAnalyzer - Analyzing user intent for query: 我想去巴黎旅游
2025-10-26 20:00:01 [main] INFO  AgentOrchestrator - Executing 3 agents in parallel
2025-10-26 20:00:01 [pool-1-thread-1] INFO  AgentOrchestrator - Executing agent: translator
2025-10-26 20:00:01 [pool-1-thread-2] INFO  AgentOrchestrator - Executing agent: travel-recommender
2025-10-26 20:00:01 [pool-1-thread-3] INFO  AgentOrchestrator - Executing agent: hotel-booking
2025-10-26 20:00:03 [main] INFO  RouterService - Query routing complete: 3/3 agents in 2500ms
```

## 🛠️ 故障排查

### Agent 未被选中

**问题**：某个 Agent 应该被选中但没有

**解决**：
1. 检查 Agent 配置中的 `skills` 是否正确
2. 检查 Agent 是否健康（`enabled: true`）
3. 查看意图分析日志

### 并行执行超时

**问题**：部分 Agent 执行超时

**解决**：
1. 增加超时时间
2. 检查慢速 Agent 的性能
3. 考虑移除超时的 Agent 结果

### 响应聚合质量差

**问题**：多个 Agent 响应聚合后不连贯

**解决**：
1. 替换 `SimpleLLMService` 为真实大模型
2. 优化聚合提示词
3. 调整查询拆分策略

## 📚 API 文档

### POST /api/router/query

**请求体**：
```json
{
  "userId": "string",
  "query": "string",
  "contextId": "string (optional)"
}
```

**响应**：
```json
{
  "requestId": "string",
  "finalResponse": "string",
  "agentResults": [
    {
      "agentName": "string",
      "query": "string",
      "success": boolean,
      "responseText": "string",
      "executionTimeMs": number
    }
  ],
  "totalExecutionTimeMs": number,
  "metadata": {
    "intents": ["string"],
    "selectedAgents": ["string"],
    "reasoning": "string",
    "confidence": number
  }
}
```

### GET /api/router/query

**查询参数**：
- `q`: 用户查询（必需）
- `userId`: 用户 ID（可选，默认 "anonymous"）

**响应**：纯文本，最终响应内容

## 🎉 下一步

1. 集成真实的大模型服务
2. 添加用户认证
3. 实现对话历史管理
4. 添加缓存机制
5. 优化意图分析准确性
6. 添加更多 Agent

---

**版本**: 1.0.0  
**完成日期**: 2025-10-26

