package com.ai.recommend.Service;

import com.ai.recommend.Service.Impl.RecommendAssistantImpl;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.document.Document;
import reactor.core.publisher.Flux;

import java.util.List;

public interface RecommendAssistant {
    record Request(String query) {}
    record Response(List<Document> content) {}
    Response travelQuery(Request message);
}
