# A2A Server 集成指南 - 旅行推荐助手

本文档说明如何使用 A2A (Agent-to-Agent) 协议将旅行推荐助手暴露为标准的 A2A Agent。

## 📋 概述

已经成功将 `RecommendAssistantAgent` 集成到 A2A Server 架构中，使其能够：

- ✅ 通过标准 JSON-RPC 2.0 协议与其他 Agent 通信
- ✅ 支持同步和流式（SSE）两种通信模式
- ✅ 提供标准化的 Agent Card 描述能力
- ✅ 保持原有的 RAG、意图识别、记忆等所有功能

## 🏗️ 架构组件

### 1. **A2AServerConfig** - A2A Server 配置类
位置: `src/main/java/com/ai/recommend/config/A2AServerConfig.java`

功能:
- 创建 A2A Server Bean
- 定义 Agent Card（代理卡片）
- 配置代理能力和技能

### 2. **A2ARecommendTaskHandler** - 任务处理适配器
位置: `src/main/java/com/ai/recommend/a2a/A2ARecommendTaskHandler.java`

功能:
- 将 A2A Message 转换为 RecommendAssistant 可处理的格式
- 调用 RecommendAssistant 进行查询
- 将响应结果转换回 A2A Task 格式
- 维护会话历史和上下文

### 3. **A2AController** - A2A REST 控制器
位置: `src/main/java/com/ai/recommend/controller/A2AController.java`

提供的端点:
- `POST /a2a` - JSON-RPC 请求处理（同步）
- `POST /a2a/stream` - 流式请求处理（SSE）
- `GET /.well-known/agent-card.json` - 获取 Agent Card

## 🚀 使用方法

### 1. 启动服务

```bash
cd RecommendAssistantAgent
mvn spring-boot:run
```

默认端口: `8080`

### 2. 获取 Agent Card

```bash
curl http://localhost:8080/.well-known/agent-card.json
```

响应示例:
```json
{
  "name": "Travel Recommendation Assistant",
  "description": "Intelligent travel recommendation assistant powered by RAG technology",
  "url": "http://localhost:8080/a2a",
  "version": "1.0.0",
  "capabilities": {
    "streaming": true,
    "pushNotifications": true,
    "stateTransitionHistory": true
  },
  "skills": [
    {
      "id": "travel-recommendation",
      "name": "Travel Recommendation Service",
      "tags": ["travel", "recommendation", "rag", "intelligent-search"]
    }
  ]
}
```

### 3. 发送消息（同步方式）

```bash
curl -X POST http://localhost:8080/a2a \
  -H "Content-Type: application/json" \
  -d '{
    "jsonrpc": "2.0",
    "id": "req-001",
    "method": "message/send",
    "params": {
      "id": "task-001",
      "message": {
        "messageId": "msg-001",
        "kind": "message",
        "role": "user",
        "parts": [
          {
            "text": "推荐一些北京的必去景点",
            "kind": "text"
          }
        ],
        "contextId": "conv-123"
      }
    }
  }'
```

响应示例:
```json
{
  "jsonrpc": "2.0",
  "id": "req-001",
  "result": {
    "id": "task-001",
    "contextId": "conv-123",
    "kind": "task",
    "status": {
      "state": "COMPLETED",
      "message": null,
      "timestamp": "2025-10-26T10:30:00Z"
    },
    "history": [
      {
        "messageId": "msg-001",
        "role": "user",
        "parts": [{"text": "推荐一些北京的必去景点", "kind": "text"}]
      },
      {
        "messageId": "msg-002",
        "role": "assistant",
        "parts": [{"text": "根据攻略推荐...", "kind": "text"}]
      }
    ]
  }
}
```

### 4. 发送消息（流式方式）

```bash
curl -X POST http://localhost:8080/a2a/stream \
  -H "Content-Type: application/json" \
  -H "Accept: text/event-stream" \
  -d '{
    "jsonrpc": "2.0",
    "id": "req-002",
    "method": "message/send",
    "params": {
      "id": "task-002",
      "message": {
        "messageId": "msg-003",
        "kind": "message",
        "role": "user",
        "parts": [
          {
            "text": "杭州有什么好玩的地方？",
            "kind": "text"
          }
        ],
        "contextId": "conv-456"
      }
    }
  }'
```

SSE 响应流:
```
event: task-update
data: {"jsonrpc":"2.0","id":"req-002","result":{"id":"task-002","status":{"state":"WORKING","timestamp":"2025-10-26T10:31:00Z"},"final":false}}

event: task-update
data: {"jsonrpc":"2.0","id":"req-002","result":{"id":"task-002","status":{"state":"COMPLETED","timestamp":"2025-10-26T10:31:05Z"},"final":true}}
```

### 5. 查询任务状态

```bash
curl -X POST http://localhost:8080/a2a \
  -H "Content-Type: application/json" \
  -d '{
    "jsonrpc": "2.0",
    "id": "req-003",
    "method": "tasks/get",
    "params": {
      "id": "task-001",
      "historyLength": 10
    }
  }'
```

### 6. 取消任务

```bash
curl -X POST http://localhost:8080/a2a \
  -H "Content-Type: application/json" \
  -d '{
    "jsonrpc": "2.0",
    "id": "req-004",
    "method": "tasks/cancel",
    "params": {
      "id": "task-001"
    }
  }'
```

## 🔧 配置选项

在 `application.yml` 或 `application.properties` 中配置:

```yaml
# 服务器端口
server:
  port: 8080

# A2A Agent 配置
a2a:
  agent:
    name: "Travel Recommendation Assistant"
    description: "Intelligent travel recommendation assistant powered by RAG technology"
```

## 💡 核心特性

### 1. **会话连续性**
- 使用 `contextId` 维护会话上下文
- 自动管理 ChatMemory，支持多轮对话

### 2. **智能推荐**
- 保留原有的 RAG 检索能力
- 意图识别和查询拆分
- 并行搜索和结果重排序

### 3. **流式响应**
- 支持 SSE (Server-Sent Events)
- 实时任务状态更新

### 4. **任务管理**
- 任务状态追踪：WORKING → COMPLETED/FAILED/CANCELED
- 任务历史记录
- 错误处理和恢复

## 🔍 工作流程

```
客户端请求
    ↓
A2AController (接收 JSON-RPC 请求)
    ↓
A2AServer (路由到相应处理器)
    ↓
A2ARecommendTaskHandler (适配器)
    ├─ 提取文本内容
    ├─ 调用 RecommendAssistant.travelQuery()
    │   └─ 意图识别 → 查询拆分 → 并行检索 → Rerank → 生成响应
    └─ 转换为 A2A Task 格式
    ↓
返回响应给客户端
```

## 📝 注意事项

1. **并发处理**: A2A Server 使用 `ConcurrentHashMap` 存储任务状态，支持并发请求

2. **会话管理**: 
   - 使用 `contextId` 作为 `chatId`
   - 每个会话独立维护记忆

3. **错误处理**:
   - 任务失败时返回 `FAILED` 状态
   - 详细错误信息包含在 `status.message` 中

4. **流式响应**:
   - 当前实现收集完整响应后返回
   - 可以进一步优化为真正的流式传输

## 🛠️ 与原有接口的对比

### 原有接口
```
GET /api/assistant/chat?chatId=xxx&userMessage=xxx
→ 返回: Flux<String> (流式文本)
```

### A2A 接口
```
POST /a2a
{
  "method": "message/send",
  "params": {"message": {...}}
}
→ 返回: JSONRPCResponse with Task (包含完整对话历史)
```

**优势:**
- ✅ 标准化协议，易于与其他 Agent 集成
- ✅ 完整的任务状态管理
- ✅ 支持消息历史追踪
- ✅ 更好的错误处理机制

## 🧪 测试建议

1. **单元测试**: 测试 A2ARecommendTaskHandler 的消息转换逻辑
2. **集成测试**: 测试完整的 A2A 请求-响应流程
3. **性能测试**: 验证并发请求处理能力
4. **流式测试**: 验证 SSE 连接的稳定性

## 📚 相关资源

- [A2A 协议规范](https://github.com/google/a2a)
- [JSON-RPC 2.0 规范](https://www.jsonrpc.org/specification)
- [Spring AI 文档](https://docs.spring.io/spring-ai/reference/)

## ✅ 下一步

1. **增强流式能力**: 将 Flux 响应真正流式传输给客户端
2. **添加认证**: 实现 Bearer Token 认证
3. **监控和日志**: 添加详细的监控指标
4. **负载均衡**: 支持多实例部署
5. **持久化**: 将任务状态持久化到数据库

---

集成完成！现在您的旅行推荐助手已经是一个标准的 A2A Agent，可以与其他遵循 A2A 协议的 Agent 进行无缝通信。

