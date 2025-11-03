package com.ai.recommend.controller;

import com.travelassistant.common.model.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ai.recommend.registry.A2AServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

import java.time.Instant;
import java.util.concurrent.CompletableFuture;

/**
 * A2A (Agent-to-Agent) REST 控制器
 * 
 * 提供标准的 A2A 协议端点，使旅行推荐助手能够与其他 Agent 进行通信
 */
@RestController
public class A2AController {
    
    private static final Logger logger = LoggerFactory.getLogger(A2AController.class);
    
    private final A2AServer server;
    private final ObjectMapper objectMapper;

    public A2AController(A2AServer server, ObjectMapper objectMapper) {
        this.server = server;
        this.objectMapper = objectMapper;
    }

    /**
     * 处理 JSON-RPC 请求（同步方式）
     * 
     * 端点: POST /a2a
     * 支持的方法:
     * - message/send: 发送消息/任务
     * - tasks/get: 查询任务状态
     * - tasks/cancel: 取消任务
     */
    @PostMapping(
        path = "/a2a",
        consumes = MediaType.APPLICATION_JSON_VALUE,
        produces = MediaType.APPLICATION_JSON_VALUE
    )
    public ResponseEntity<JSONRPCResponse> handleJsonRpcRequest(@RequestBody JSONRPCRequest request) {
        logger.info("收到 JSON-RPC 请求: method={}, id={}", request.method(), request.id());
        
        // 验证 JSON-RPC 版本
        if (!"2.0".equals(request.jsonrpc())) {
            logger.warn("无效的 JSON-RPC 版本: {}", request.jsonrpc());
            JSONRPCError error = new JSONRPCError(
                ErrorCode.INVALID_REQUEST.getValue(),
                "Invalid JSON-RPC version, expected 2.0",
                null
            );
            JSONRPCResponse response = new JSONRPCResponse(
                request.id(),
                "2.0",
                null,
                error
            );
            return ResponseEntity.badRequest().body(response);
        }

        // 根据方法分发请求
        JSONRPCResponse response = switch (request.method()) {
            case "message/send" -> {
                logger.info("处理消息发送请求");
                yield server.handleTaskSend(request);
            }
            case "tasks/get" -> {
                logger.info("处理任务查询请求");
                yield server.handleTaskGet(request);
            }
            case "tasks/cancel" -> {
                logger.info("处理任务取消请求");
                yield server.handleTaskCancel(request);
            }
            default -> {
                logger.warn("未知的方法: {}", request.method());
                JSONRPCError error = new JSONRPCError(
                    ErrorCode.METHOD_NOT_FOUND.getValue(),
                    "Method not found: " + request.method(),
                    null
                );
                yield new JSONRPCResponse(
                    request.id(),
                    "2.0",
                    null,
                    error
                );
            }
        };

        logger.info("请求处理完成: hasError={}", response.error() != null);
        return ResponseEntity.ok(response);
    }

    /**
     * 处理流式任务请求（Server-Sent Events）
     * 
     * 端点: POST /a2a/stream
     * 使用响应式 Flux 实现实时任务状态更新
     */
    @PostMapping(
        value = "/a2a/stream",
        consumes = MediaType.APPLICATION_JSON_VALUE,
        produces = MediaType.TEXT_EVENT_STREAM_VALUE
    )
    public Flux<String> handleStreamingTask(@RequestBody JSONRPCRequest request) {
        logger.info("收到流式请求: method={}, id={}", request.method(), request.id());
        
        // 验证方法
        if (!"message/send".equals(request.method())) {
            return Flux.error(new IllegalArgumentException(
                "Only message/send is supported for streaming"));
        }
        
        try {
            // 解析参数
            TaskSendParams params = parseTaskSendParams(request.params());
            
            // 创建响应式流
            return Flux.<String>create(sink -> {
                try {
                    // 发送初始状态
                    TaskStatus initialStatus = new TaskStatus(
                        TaskState.WORKING,
                        null,
                        Instant.now().toString()
                    );
                    
                    TaskStatusUpdateEvent initialEvent = new TaskStatusUpdateEvent(
                        params.id(),
                        initialStatus,
                        false,
                        null
                    );
                    
                    SendTaskStreamingResponse initialResponse = new SendTaskStreamingResponse(
                        request.id(),
                        "2.0",
                        initialEvent,
                        null
                    );
                    
                    logger.info("发送初始状态事件");
                    sink.next(objectMapper.writeValueAsString(initialResponse));
                    
                    // 异步处理任务
                    CompletableFuture.runAsync(() -> {
                        try {
                            // 处理任务
                            JSONRPCResponse taskResponse = server.handleTaskSend(request);
                            
                            if (taskResponse.error() != null) {
                                A2AError error = new A2AError(
                                    ErrorCode.INTERNAL_ERROR,
                                    taskResponse.error().message(),
                                    null
                                );
                                SendTaskStreamingResponse errorResponse = new SendTaskStreamingResponse(
                                    request.id(),
                                    "2.0",
                                    null,
                                    error
                                );
                                sink.next(objectMapper.writeValueAsString(errorResponse));
                                sink.complete();
                                return;
                            }
                            
                            // 发送最终状态
                            Task completedTask = (Task) taskResponse.result();
                            TaskStatusUpdateEvent finalEvent = new TaskStatusUpdateEvent(
                                completedTask.id(),
                                completedTask.status(),
                                true,
                                null
                            );
                            
                            SendTaskStreamingResponse finalResponse = new SendTaskStreamingResponse(
                                request.id(),
                                "2.0",
                                finalEvent,
                                null
                            );
                            
                            logger.info("发送最终状态事件");
                            sink.next(objectMapper.writeValueAsString(finalResponse));
                            sink.complete();
                            logger.info("流式任务完成");
                            
                        } catch (Exception e) {
                            logger.error("流式任务处理失败", e);
                            sink.error(e);
                        }
                    });
                    
                } catch (Exception e) {
                    logger.error("创建流式响应失败", e);
                    sink.error(e);
                }
            })
            .subscribeOn(Schedulers.boundedElastic())
            .doOnError(error -> logger.error("流式响应错误", error))
            .onErrorResume(error -> {
                // 发送错误响应
                try {
                    A2AError a2aError = new A2AError(
                        ErrorCode.INTERNAL_ERROR,
                        error.getMessage(),
                        null
                    );
                    SendTaskStreamingResponse errorResponse = new SendTaskStreamingResponse(
                        request.id(),
                        "2.0",
                        null,
                        a2aError
                    );
                    return Flux.just(objectMapper.writeValueAsString(errorResponse));
                } catch (Exception e) {
                    return Flux.error(error);
                }
            });
            
        } catch (Exception e) {
            logger.error("解析请求参数失败", e);
            return Flux.error(e);
        }
    }

    /**
     * 获取 Agent Card
     * 
     * 端点: GET /.well-known/agent-card.json
     * 返回代理的能力描述
     */
    @GetMapping("/.well-known/agent-card.json")
    public ResponseEntity<AgentCard> getAgentCard() {
        logger.info("获取 Agent Card");
        AgentCard agentCard = server.getAgentCard();
        return ResponseEntity.ok(agentCard);
    }

    /**
     * 解析任务发送参数
     */
    private TaskSendParams parseTaskSendParams(Object params) throws Exception {
        return objectMapper.convertValue(params, TaskSendParams.class);
    }
}

