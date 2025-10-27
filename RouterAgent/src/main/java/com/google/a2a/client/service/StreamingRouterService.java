package com.google.a2a.client.service;

import com.google.a2a.client.model.RoutedRequest;
import reactor.core.publisher.Flux;

public interface StreamingRouterService {
    Flux<String> routeStreaming(RoutedRequest request);

    Flux<String> fetchAgentStreamWithQuery(String agentName, String query);
}
