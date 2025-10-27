package com.google.a2a.client.service.impl;

import com.google.a2a.client.aggregator.AggregatorAgent;
import com.google.a2a.client.component.analyzer.IntentAnalyzer;
import com.google.a2a.client.model.IntentAnalysisResult;
import com.google.a2a.client.model.RoutedRequest;
import com.google.a2a.client.service.StreamingRouterService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 流式路由服务实现
 * <p>
 * 整合了 IntentAnalyzer、AgentOrchestrator 和 AggregatorAgent
 * 核心流程：
 * 1. 意图分析，获取需要调用的 Agent 列表
 * 2. Fan-out: 并行调用所有 Agent 的流式接口
 * 3. Fan-in: 使用 Flux.merge() 合并所有流
 * 4. 增量聚合: 使用 AggregatorAgent 进行 Token 重新生成
 */
@Service
public class StreamingRouterServiceImpl implements StreamingRouterService {

    private static final Logger logger = LoggerFactory.getLogger(StreamingRouterServiceImpl.class);

    private final IntentAnalyzer intentAnalyzer;
    private final WebClient webClient;
    private final AggregatorAgent aggregatorAgent;
    
    public StreamingRouterServiceImpl(
            IntentAnalyzer intentAnalyzer, 
            WebClient.Builder webClientBuilder,
            AggregatorAgent aggregatorAgent) {
        this.intentAnalyzer = intentAnalyzer;
        this.webClient = webClientBuilder.build();
        this.aggregatorAgent = aggregatorAgent;
    }

    /**
     * 流式路由主方法
     * 
     * @param request 路由请求
     * @return 流式 Token 流（Flux<String>）
     */
    @Override
    public Flux<String> routeStreaming(RoutedRequest request) {
        logger.info("[StreamingRouterService] Starting streaming route for request: {}", request.query());
        
        try {
            // 步骤 1: 分析用户意图，获取需要调用的 Agent 列表
            logger.info("[Step 1/3] Analyzing intent...");
            IntentAnalysisResult analysis = intentAnalyzer.analyze(request.query());
            
            if (analysis.selectedAgents().isEmpty()) {
                logger.warn("No agents selected for streaming");
                return Flux.just("抱歉，未找到合适的 Agent 处理您的请求。");
            }
            
            logger.info("[Step 1/3] Selected {} agents: {}", 
                    analysis.selectedAgents().size(), 
                    analysis.selectedAgents());
            
            // 步骤 2: 并行获取所有 Agent 的流
            logger.info("[Step 2/3] Fan-out: Fetching {} agent streams in parallel...", 
                    analysis.selectedAgents().size());
            
            List<Flux<String>> agentStreams = analysis.selectedAgents().stream()
                    .map(agentName -> fetchAgentStreamWithQuery(agentName, 
                            analysis.splitQueries().getOrDefault(agentName, request.query())))
                    .collect(Collectors.toList());
            
            // 步骤 3: 使用 Flux.merge() 合并所有流
            logger.info("[Step 3/3] Fan-in: Merging {} streams...", agentStreams.size());
            Flux<String> mergedStream = Flux.merge(agentStreams);
            
            // 步骤 4: 使用 AggregatorAgent 进行增量聚合
            logger.info("[Step 4] Incremental aggregation using AggregatorAgent...");
            return aggregatorAgent.aggregateAndStream(mergedStream, request.query());
            
        } catch (Exception e) {
            logger.error("Failed to create streaming route", e);
            return Flux.just("处理请求时出现错误: " + e.getMessage());
        }
    }

    /**
     * 从单个 Agent 获取流（带专属查询）
     */
    @Override
    public Flux<String> fetchAgentStreamWithQuery(String agentName, String query) {
        return Flux.defer(() -> {
            String url = "http://localhost:8080/api/" + agentName + "/stream";
            
            logger.info("Fetching stream from {} with query: {}", agentName, query);
            
            // 使用 WebClient 调用 Agent 的流式接口
            // 实际实现应该根据 agentName 和配置获取正确的 URL
            return webClient.post()
                    .uri(url)
                    .bodyValue(Map.of("query", query))
                    .retrieve()
                    .bodyToFlux(String.class)
                    .doOnNext(token -> logger.debug("Received token from {}: {}", agentName, token))
                    .onErrorResume(e -> {
                        logger.error("Error fetching stream from agent {}: {}", agentName, e.getMessage());
                        return Flux.just("[Agent " + agentName + " error: " + e.getMessage() + "]");
                    });
        })
        .subscribeOn(Schedulers.parallel());
    }
}

