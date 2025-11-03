package com.google.a2a.client.manager;

import com.google.a2a.client.Exception.A2AClientException;
import com.google.a2a.client.listener.StreamingEventListener;
import com.travelassistant.common.model.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * A2A protocol client implementation
 */
public class A2AClient {
    
    private static final Logger logger = LoggerFactory.getLogger(A2AClient.class);
    
    private final String baseUrl;
    private final HttpClient httpClient;
    private final WebClient webClient;
    private final ObjectMapper objectMapper;
    
    /**
     * Create a new A2A client
     * 
     * @param baseUrl the base URL of the A2A server
     */
    public A2AClient(String baseUrl) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .build();
        this.webClient = WebClient.builder()
            .baseUrl(this.baseUrl)
            .build();
        this.objectMapper = new ObjectMapper();
    }
    
    /**
     * Create a new A2A client with custom HTTP client
     * 
     * @param baseUrl the base URL of the A2A server
     * @param httpClient custom HTTP client
     */
    public A2AClient(String baseUrl, HttpClient httpClient) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.httpClient = httpClient;
        this.webClient = WebClient.builder()
            .baseUrl(this.baseUrl)
            .build();
        this.objectMapper = new ObjectMapper();
    }
    
    /**
     * Create a new A2A client with custom WebClient
     * 
     * @param baseUrl the base URL of the A2A server
     * @param webClient custom WebClient for reactive streaming
     */
    public A2AClient(String baseUrl, WebClient webClient) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .build();
        this.webClient = webClient;
        this.objectMapper = new ObjectMapper();
    }
    
    /**
     * Send a task message to the agent
     * 
     * @param params task send parameters
     * @return JSON-RPC response containing the task
     * @throws A2AClientException if the request fails
     */
    public JSONRPCResponse sendTask(TaskSendParams params) throws A2AClientException {
        JSONRPCRequest request = new JSONRPCRequest(
            generateRequestId(),
            "2.0",
            "message/send",
            params
        );
        
        return doRequest(request);
    }
    
    /**
     * Get the status of a task
     * 
     * @param params task query parameters
     * @return JSON-RPC response containing the task
     * @throws A2AClientException if the request fails
     */
    public JSONRPCResponse getTask(TaskQueryParams params) throws A2AClientException {
        JSONRPCRequest request = new JSONRPCRequest(
            generateRequestId(),
            "2.0",
            "tasks/get",
            params
        );
        
        return doRequest(request);
    }
    
    /**
     * Cancel a task
     * 
     * @param params task ID parameters
     * @return JSON-RPC response containing the task
     * @throws A2AClientException if the request fails
     */
    public JSONRPCResponse cancelTask(TaskIDParams params) throws A2AClientException {
        JSONRPCRequest request = new JSONRPCRequest(
            generateRequestId(),
            "2.0",
            "tasks/cancel",
            params
        );
        
        return doRequest(request);
    }
    
    /**
     * Send a task with streaming response
     * 
     * 使用 WebClient 处理 SSE 流式响应，支持实时数据流
     * 
     * @param params task send parameters
     * @param listener event listener for streaming updates
     * @return CompletableFuture that completes when streaming ends
     */
    public CompletableFuture<Void> sendTaskStreaming(TaskSendParams params, StreamingEventListener listener) {
        return CompletableFuture.runAsync(() -> {
            try {
                JSONRPCRequest request = new JSONRPCRequest(
                    generateRequestId(),
                    "2.0",
                    "message/send",
                    params
                );
                
                // 使用 WebClient 接收 SSE 流式响应
                Flux<String> responseFlux = webClient.post()
                    .uri("/a2a/stream")
                    .bodyValue(request)
                    .retrieve()
                    .bodyToFlux(String.class);  // 自动解析 SSE 格式
                
                // 订阅流并处理事件
                responseFlux
                    .doOnNext(line -> {
                        logger.debug("Received SSE line: [{}]", line);
                        
                        // SSE 格式: "data: {json}\n\n" 或直接是 JSON 字符串
                        String jsonData = extractJsonFromSseLine(line);
                        if (jsonData == null || jsonData.isEmpty()) {
                            logger.debug("Skipping empty or non-JSON SSE line");
                            return;
                        }
                        
                        logger.debug("Extracted JSON from SSE: {}", jsonData);
                        
                        try {
                            SendTaskStreamingResponse streamingResponse = objectMapper.readValue(
                                jsonData, 
                                SendTaskStreamingResponse.class
                            );
                            
                            if (streamingResponse.error() != null) {
                                A2AError error = streamingResponse.error();
                                Integer errorCode = error.code() != null ? error.code().getValue() : null;
                                logger.error("A2A streaming error: {} (code: {})", error.message(), errorCode);
                                listener.onError(new A2AClientException(
                                    error.message(),
                                    errorCode
                                ));
                                return;
                            }
                            
                            if (streamingResponse.result() != null) {
                                logger.debug("Received A2A event: {}", 
                                    streamingResponse.result().getClass().getSimpleName());
                                listener.onEvent(streamingResponse.result());
                            } else {
                                logger.debug("Received A2A response with null result");
                            }
                            
                        } catch (Exception e) {
                            logger.error("Failed to parse streaming response: {}", jsonData, e);
                            listener.onError(new A2AClientException(
                                "Failed to parse streaming response: " + e.getMessage(), 
                                e
                            ));
                        }
                    })
                    .doOnComplete(() -> {
                        logger.debug("SSE stream completed");
                        listener.onComplete();
                    })
                    .doOnError(error -> {
                        logger.error("SSE stream error", error);
                        listener.onError(
                            new A2AClientException("Streaming request failed", error)
                        );
                    })
                    .blockLast();  // 阻塞直到流完成
                
            } catch (Exception e) {
                listener.onError(new A2AClientException("Streaming request failed", e));
            }
        });
    }
    
    /**
     * 从 SSE 行中提取 JSON 数据
     * SSE 格式: "data: {json}\n\n" 或直接是 JSON 字符串
     * 
     * WebClient 的 bodyToFlux(String.class) 会保留 SSE 格式，每行可能是：
     * - "data: {json}" - 标准 SSE 格式
     * - "" - 空行（分隔符）
     * - "{json}" - 纯 JSON（如果已经处理过前缀）
     */
    private String extractJsonFromSseLine(String line) {
        if (line == null || line.trim().isEmpty()) {
            return null;
        }

        // 处理 SSE 格式: "data: {json}"
        if (line.startsWith("data:")) {
            String json = line.substring(5).trim();
            // 跳过只有 "data:" 没有内容的情况
            return json.isEmpty() ? null : json;
        }

        // 处理已经是 JSON 的情况（Spring 可能已经处理了前缀）
        String trimmed = line.trim();
        if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
            return trimmed;
        }

        // 其他情况，可能是注释或其他 SSE 字段，忽略
        return null;
    }
    
    /**
     * Get agent card information
     * 
     * @return the agent card
     * @throws A2AClientException if the request fails
     */
    public AgentCard getAgentCard() throws A2AClientException {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/.well-known/agent-card.json"))
                .header("Accept", "application/json")
                .GET()
                .build();
            
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            
            if (response.statusCode() != 200) {
                throw new A2AClientException("HTTP " + response.statusCode() + ": " + response.body());
            }
            
            return objectMapper.readValue(response.body(), AgentCard.class);
            
        } catch (IOException | InterruptedException e) {
            throw new A2AClientException("Failed to get agent card", e);
        }
    }
    
    /**
     * Perform HTTP request and handle response
     */
    private JSONRPCResponse doRequest(JSONRPCRequest request) throws A2AClientException {
        try {
            String requestBody = objectMapper.writeValueAsString(request);
            
            HttpRequest httpRequest = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/a2a"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                .build();
            
            HttpResponse<String> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());
            
            if (response.statusCode() != 200) {
                throw new A2AClientException("HTTP " + response.statusCode() + ": " + response.body());
            }
            
            // Parse the response
            JsonNode responseNode = objectMapper.readTree(response.body());
            
            // Extract basic fields
            Object id = responseNode.has("id") ? responseNode.get("id").asText() : null;
            String jsonrpc = responseNode.get("jsonrpc").asText();
            
            // Handle error
            JSONRPCError error = null;
            if (responseNode.has("error") && !responseNode.get("error").isNull()) {
                error = objectMapper.treeToValue(responseNode.get("error"), JSONRPCError.class);
            }
            
            // Handle result
            Object result = null;
            if (responseNode.has("result") && !responseNode.get("result").isNull()) {
                result = objectMapper.treeToValue(responseNode.get("result"), Task.class);
            }
            
            JSONRPCResponse jsonrpcResponse = new JSONRPCResponse(id, jsonrpc, result, error);
            
            // Check for A2A errors
            if (error != null) {
                throw new A2AClientException(error.message(), error.code());
            }
            
            return jsonrpcResponse;
            
        } catch (IOException | InterruptedException e) {
            throw new A2AClientException("Request failed", e);
        }
    }
    
    /**
     * Generate a unique request ID
     */
    private String generateRequestId() {
        return UUID.randomUUID().toString();
    }
} 