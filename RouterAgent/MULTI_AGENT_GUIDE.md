# Multi-Agent Collaboration Guide

多 Agent 协作系统完整使用指南

## 📋 目录

- [概述](#概述)
- [架构设计](#架构设计)
- [快速开始](#快速开始)
- [配置说明](#配置说明)
- [核心组件](#核心组件)
- [使用示例](#使用示例)
- [最佳实践](#最佳实践)

## 概述

多 Agent 协作系统是一个完整的 Agent 管理和编排框架，支持：

✅ 动态 Agent 注册和发现  
✅ 基于配置文件的 Agent 管理  
✅ 灵活的 Agent 选择策略  
✅ 自动健康监控  
✅ 任务路由和负载均衡  
✅ 性能指标收集  

## 架构设计

```
┌─────────────────────────────────────────────────────────────┐
│                    AgentManager (核心管理器)                   │
├─────────────────────────────────────────────────────────────┤
│                                                               │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐       │
│  │ AgentRegistry │  │AgentDiscovery│  │HealthMonitor │       │
│  │  (注册表)     │  │  (发现服务)   │  │  (健康监控)  │       │
│  └──────────────┘  └──────────────┘  └──────────────┘       │
│                                                               │
│  ┌──────────────────────────────────────────────────┐       │
│  │          AgentSelector (选择器)                   │       │
│  │  • SkillBasedSelector (基于技能)                 │       │
│  │  • RoundRobinSelector (轮询)                     │       │
│  └──────────────────────────────────────────────────┘       │
│                                                               │
└─────────────────────────────────────────────────────────────┘
                            ↓
        ┌─────────────────────────────────────────┐
        │          A2A Clients                     │
        ├──────────┬──────────┬──────────┬────────┤
        │Translator│ Travel   │ Weather  │ Hotel  │
        │  Agent   │  Agent   │  Agent   │ Agent  │
        └──────────┴──────────┴──────────┴────────┘
```

## 快速开始

### 1. 创建配置文件

在 `src/main/resources/agents-config.yaml` 创建 Agent 配置：

```yaml
agents:
  - name: translator
    url: http://localhost:8080
    description: AI Translation Bot
    skills:
      - translation
      - language
    enabled: true
    priority: 10
    timeout: 30000
    retryAttempts: 3
    healthCheckInterval: 60

  - name: travel-recommender
    url: http://localhost:8081
    description: Travel Recommendation Assistant
    skills:
      - travel
      - recommendation
    enabled: true
    priority: 20
```

### 2. 初始化 AgentManager

```java
// 从配置文件初始化
AgentManager manager = AgentManager.fromClasspath("agents-config.yaml");

// 查看已加载的 Agent
List<AgentInfo> agents = manager.getAllAgents();
for (AgentInfo agent : agents) {
    System.out.println(agent.name() + " - " + agent.config().skills());
}
```

### 3. 使用 Agent

```java
// 方式 1: 直接指定 Agent
Optional<A2AClient> client = manager.getAgent("translator");
if (client.isPresent()) {
    JSONRPCResponse response = client.get().sendTask(params);
}

// 方式 2: 基于技能自动选择
Optional<A2AClient> client = manager.selectAgentBySkill("translation");
if (client.isPresent()) {
    JSONRPCResponse response = client.get().sendTask(params);
}

// 方式 3: 使用 AgentManager 直接发送
try {
    JSONRPCResponse response = manager.sendTaskBySkill("translation", params);
    System.out.println("Task completed: " + response.result());
} catch (A2AClientException e) {
    System.err.println("Task failed: " + e.getMessage());
}
```

## 配置说明

### Agent 配置项

| 配置项 | 类型 | 必需 | 默认值 | 说明 |
|--------|------|------|--------|------|
| `name` | String | ✅ | - | Agent 唯一标识 |
| `url` | String | ✅ | - | Agent 服务地址 |
| `description` | String | ❌ | "" | Agent 描述 |
| `skills` | List<String> | ❌ | [] | Agent 技能列表 |
| `enabled` | Boolean | ❌ | true | 是否启用 |
| `priority` | Integer | ❌ | 0 | 优先级（越大越高） |
| `timeout` | Long | ❌ | 30000 | 超时时间（毫秒） |
| `retryAttempts` | Integer | ❌ | 3 | 重试次数 |
| `healthCheckInterval` | Integer | ❌ | 60 | 健康检查间隔（秒） |
| `metadata` | Map | ❌ | {} | 自定义元数据 |

### 配置示例

```yaml
agents:
  - name: flight-search
    url: http://localhost:8084
    description: Flight Search Service
    skills:
      - flight
      - airline
      - booking
    enabled: true
    priority: 15
    timeout: 45000
    retryAttempts: 3
    healthCheckInterval: 90
    metadata:
      provider: FlightSearch Inc
      version: "2.0.0"
      regions:
        - Asia
        - Europe
```

## 核心组件

### 1. AgentManager

核心管理器，负责整个 Agent 生命周期管理。

```java
// 创建方式
AgentManager manager = AgentManager.fromClasspath("agents-config.yaml");
AgentManager manager = AgentManager.fromConfigFile("/path/to/config.yaml");

// 主要方法
manager.loadAgents(configs);              // 加载 Agent
manager.registerAgent(config);             // 注册单个 Agent
manager.unregisterAgent("agent-name");     // 注销 Agent
manager.selectAgentBySkill("translation"); // 选择 Agent
manager.startHealthMonitoring();           // 启动健康监控
manager.shutdown();                        // 关闭管理器
```

### 2. AgentRegistry

Agent 注册表，维护所有已注册的 Agent。

```java
// 通过 AgentManager 内部使用
Optional<A2AClient> client = registry.getClient("agent-name");
List<AgentConfig> agents = registry.findAgentsBySkill("travel");
boolean healthy = registry.isHealthy("agent-name");
```

### 3. AgentDiscovery

Agent 发现服务，负责连接和验证 Agent。

```java
// 自动发现并注册
boolean success = discovery.discoverAgent(config);

// 重新发现（刷新 Agent Card）
boolean success = discovery.rediscoverAgent("agent-name");

// 验证连接
boolean valid = discovery.validateAgent("agent-name");
```

### 4. AgentSelector

Agent 选择策略。

**SkillBasedSelector** - 基于技能和优先级选择

```java
manager.setSelector(new SkillBasedSelector());
```

**RoundRobinSelector** - 轮询选择（负载均衡）

```java
manager.setSelector(new RoundRobinSelector());
```

**自定义选择器**

```java
public class CustomSelector implements AgentSelector {
    @Override
    public AgentConfig select(List<AgentConfig> candidates, List<String> skills) {
        // 自定义选择逻辑
        return candidates.get(0);
    }
}

manager.setSelector(new CustomSelector());
```

### 5. AgentHealthMonitor

健康监控服务，定期检查 Agent 状态。

```java
// 启动监控（默认30秒检查一次）
manager.startHealthMonitoring();

// 获取指标
Map<String, AgentMetrics> metrics = healthMonitor.getAllMetrics();
for (AgentMetrics metric : metrics.values()) {
    System.out.println(metric); // 包含成功率、响应时间等
}

// 停止监控
manager.stopHealthMonitoring();
```

## 使用示例

### 示例 1: 简单的单 Agent 使用

```java
AgentManager manager = AgentManager.fromClasspath("agents-config.yaml");

// 获取翻译 Agent
Optional<A2AClient> translator = manager.getAgent("translator");

if (translator.isPresent()) {
    // 创建翻译任务
    Message message = createTextMessage("Hello, World!");
    TaskSendParams params = new TaskSendParams(
        "task-001",
        null,
        message,
        null, null,
        Map.of()
    );
    
    // 发送任务
    JSONRPCResponse response = translator.get().sendTask(params);
    System.out.println("Translation: " + extractResponse(response));
}

manager.shutdown();
```

### 示例 2: 多 Agent 协作场景

```java
AgentManager manager = AgentManager.fromClasspath("agents-config.yaml");

// 场景：规划巴黎之旅
System.out.println("Planning a trip to Paris...");

// 1. 翻译景点名称
var translator = manager.selectAgentBySkill("translation");
translator.ifPresent(client -> {
    // 发送翻译任务
    // ...
});

// 2. 获取旅行推荐
var travelAgent = manager.selectAgentBySkill("travel");
travelAgent.ifPresent(client -> {
    // 获取推荐
    // ...
});

// 3. 查询天气
var weatherAgent = manager.selectAgentBySkill("weather");
weatherAgent.ifPresent(client -> {
    // 查询天气
    // ...
});

// 4. 预订酒店
var hotelAgent = manager.selectAgentBySkill("hotel");
hotelAgent.ifPresent(client -> {
    // 预订酒店
    // ...
});

System.out.println("Trip planning completed!");
manager.shutdown();
```

### 示例 3: 使用健康监控

```java
AgentManager manager = AgentManager.fromClasspath("agents-config.yaml");

// 启动健康监控
manager.startHealthMonitoring();

// 运行一段时间后检查健康状态
Thread.sleep(60000);

// 获取健康的 Agent
List<String> healthyAgents = manager.getHealthyAgents();
System.out.println("Healthy agents: " + healthyAgents);

// 获取所有 Agent 的详细状态
List<AgentInfo> allAgents = manager.getAllAgents();
for (AgentInfo agent : allAgents) {
    System.out.printf("%s: %s (priority: %d)%n",
        agent.name(),
        agent.healthy() ? "✓" : "✗",
        agent.config().priority()
    );
}

manager.stopHealthMonitoring();
manager.shutdown();
```

### 示例 4: 异步加载 Agent

```java
AgentManager manager = new AgentManager();

// 加载配置
List<AgentConfig> configs = AgentsConfigLoader.loadFromClasspath("agents-config.yaml");

// 异步加载所有 Agent
CompletableFuture<Void> loadingFuture = manager.loadAgentsAsync(configs);

// 等待加载完成
loadingFuture.thenRun(() -> {
    System.out.println("All agents loaded!");
    
    // 使用 Agent
    List<AgentInfo> agents = manager.getAllAgents();
    System.out.println("Total agents: " + agents.size());
});

// 或者阻塞等待
loadingFuture.join();
```

## 最佳实践

### 1. 配置管理

✅ **推荐**：将不同环境的配置分开
```
resources/
  ├── agents-config-dev.yaml
  ├── agents-config-staging.yaml
  └── agents-config-prod.yaml
```

✅ **推荐**：使用环境变量覆盖配置
```yaml
agents:
  - name: translator
    url: ${TRANSLATOR_URL:http://localhost:8080}
```

### 2. 错误处理

```java
try {
    JSONRPCResponse response = manager.sendTaskBySkill("translation", params);
    // 处理成功
} catch (A2AClientException e) {
    // 记录错误
    logger.error("Task failed", e);
    
    // 尝试备用 Agent
    var backupAgent = manager.selectAgentBySkill("translation");
    // ...
}
```

### 3. 健康监控

```java
// 在应用启动时启动监控
manager.startHealthMonitoring();

// 定期打印健康报告
scheduler.scheduleAtFixedRate(() -> {
    healthMonitor.printHealthStatus();
}, 0, 5, TimeUnit.MINUTES);

// 在应用关闭时停止监控
Runtime.getRuntime().addShutdownHook(new Thread(() -> {
    manager.stopHealthMonitoring();
    manager.shutdown();
}));
```

### 4. 选择器策略

```java
// 开发环境：使用技能选择器
if ("dev".equals(env)) {
    manager.setSelector(new SkillBasedSelector());
}

// 生产环境：使用轮询实现负载均衡
if ("prod".equals(env)) {
    manager.setSelector(new RoundRobinSelector());
}
```

### 5. 资源管理

```java
// 使用 try-with-resources（需要实现 AutoCloseable）
public class ManagedAgentManager implements AutoCloseable {
    private final AgentManager manager;
    
    public ManagedAgentManager(String configPath) throws IOException {
        this.manager = AgentManager.fromClasspath(configPath);
    }
    
    @Override
    public void close() {
        manager.shutdown();
    }
}

// 使用
try (ManagedAgentManager manager = new ManagedAgentManager("agents-config.yaml")) {
    // 使用 manager
}
```

## 运行示例

```bash
# 编译项目
mvn clean compile

# 运行多 Agent 示例
mvn exec:java -Dexec.mainClass="com.google.a2a.client.MultiAgentExample"
```

## 故障排查

### Agent 无法连接

1. 检查 URL 是否正确
2. 确认 Agent 服务是否运行
3. 查看日志中的错误信息

### Agent 被标记为不健康

1. 检查网络连接
2. 验证 Agent Card 端点是否可访问
3. 调整 `healthCheckInterval` 和 `timeout`

### 找不到合适的 Agent

1. 检查技能配置是否正确
2. 确认 Agent 已启用（`enabled: true`）
3. 验证 Agent 健康状态

## 下一步

- 查看完整的 API 文档
- 了解自定义选择器的实现
- 学习 Agent 性能优化技巧
- 集成到您的应用程序中

---

**版本**: 1.0.0  
**更新日期**: 2025-10-26

