package com.ai.recommend.Service;

import org.springframework.ai.chat.model.ChatResponse;
import reactor.core.publisher.Flux;

public interface RecommendAssistant {
    Flux<ChatResponse> travelQuery(String chatId,String message);
}
