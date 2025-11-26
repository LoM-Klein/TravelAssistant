package com.ai.recommend.config.embedding;

import com.knuddels.jtokkit.api.EncodingType;
import org.springframework.ai.embedding.BatchingStrategy;
import org.springframework.ai.embedding.TokenCountBatchingStrategy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class EmbeddingConfig {
    @Bean
    public BatchingStrategy dualLimitBatchingStrategy() {
        return new TripleLimitBatchingStrategy();
    }
}