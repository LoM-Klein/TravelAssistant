package com.google.a2a.client.controller;

import com.google.a2a.client.model.RoutedRequest;
import com.google.a2a.client.model.vo.QueryRequest;
import com.google.a2a.client.service.StreamingRouterService;
import com.google.a2a.client.service.impl.StreamingRouterServiceImpl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;

/**
 * 路由 Agent Controller
 * <p>
 * 接收用户请求，路由到合适的 Agent 并返回聚合响应
 * 支持普通响应和流式响应两种模式
 */
@RestController
@RequestMapping("/api/router")
public class RouterController {
    
    private static final Logger logger = LoggerFactory.getLogger(RouterController.class);

    private final StreamingRouterService streamingRouterService;
    
    public RouterController(

            StreamingRouterServiceImpl streamingRouterService) {
        this.streamingRouterService = streamingRouterService;
    }

    
    /**
     * 流式路由请求（Server-Sent Events）
     * POST /api/router/stream
     * {
     *   "userId": "user123",
     *   "query": "我想去巴黎旅游，帮我翻译景点名称并推荐酒店",
     *   "contextId": "conv-456"
     * }
     * 
     * 返回: text/event-stream 格式的流式响应
     */
    @PostMapping(
        path = "/stream",
        consumes = MediaType.APPLICATION_JSON_VALUE,
        produces = MediaType.TEXT_EVENT_STREAM_VALUE
    )
    public Flux<String> routeStreaming(@RequestBody QueryRequest request) {
        logger.info("Received streaming query from user: {} - {}", 
                request.getUserId(), request.getQuery());
        
        // 创建路由请求
        RoutedRequest routedRequest = RoutedRequest.create(
                request.getUserId(),
                request.getQuery(),
                request.getContextId()
        );
        
        // 返回流式响应
        return streamingRouterService.routeStreaming(routedRequest);
    }
    
    /**
     * 简化的流式查询接口
     * GET /api/router/stream?q=我想去巴黎旅游
     */
    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> simpleStreamingQuery(
            @RequestParam("q") String query,
            @RequestParam(value = "userId", defaultValue = "anonymous") String userId) {
        
        logger.info("Received simple streaming query from {}: {}", userId, query);
        
        RoutedRequest request = RoutedRequest.create(userId, query, null);
        return streamingRouterService.routeStreaming(request);
    }

}

