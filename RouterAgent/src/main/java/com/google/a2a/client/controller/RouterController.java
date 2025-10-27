package com.google.a2a.client.controller;

import com.google.a2a.client.model.RoutedRequest;
import com.google.a2a.client.model.RoutedResponse;
import com.google.a2a.client.model.vo.QueryRequest;
import com.google.a2a.client.service.RouterService;
import com.google.a2a.client.service.StreamingRouterService;
import com.google.a2a.client.service.impl.RouterServiceImpl;
import com.google.a2a.client.service.impl.StreamingRouterServiceImpl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
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
    private final RouterService routerService;
    private final StreamingRouterService streamingRouterService;
    
    public RouterController(
            RouterServiceImpl routerService,
            StreamingRouterServiceImpl streamingRouterService) {
        this.routerService = routerService;
        this.streamingRouterService = streamingRouterService;
    }
    
    /**
     * 路由请求
     * POST /api/router/query
     * {
     *   "userId": "user123",
     *   "query": "我想去巴黎旅游，帮我翻译景点名称并推荐酒店",
     *   "contextId": "conv-456"
     * }
     */
    @PostMapping(
        path = "/query",
        consumes = MediaType.APPLICATION_JSON_VALUE,
        produces = MediaType.APPLICATION_JSON_VALUE
    )
    public ResponseEntity<RoutedResponse> routeQuery(@RequestBody QueryRequest request) {
        logger.info("Received query from user: {} - {}", 
                request.getUserId(), request.getQuery());
        
        // 创建路由请求
        RoutedRequest routedRequest = RoutedRequest.create(
                request.getUserId(),
                request.getQuery(),
                request.getContextId()
        );
        
        // 执行路由
        RoutedResponse response = routerService.route(routedRequest);
        
        logger.info("Query routing complete: {} agents executed in {}ms",
                response.agentResults().size(),
                response.totalExecutionTimeMs());
        
        return ResponseEntity.ok(response);
    }
    
    /**
     * 简化的查询接口
     * GET /api/router/query?q=我想去巴黎旅游
     */
    @GetMapping("/query")
    public ResponseEntity<String> simpleQuery(
            @RequestParam("q") String query,
            @RequestParam(value = "userId", defaultValue = "anonymous") String userId) {
        
        logger.info("Received simple query from {}: {}", userId, query);
        
        RoutedRequest request = RoutedRequest.create(userId, query, null);
        RoutedResponse response = routerService.route(request);
        
        return ResponseEntity.ok(response.finalResponse());
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

