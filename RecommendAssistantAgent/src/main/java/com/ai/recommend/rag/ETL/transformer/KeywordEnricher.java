package com.ai.recommend.rag.ETL.transformer;


import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.ai.document.Document;
import org.springframework.ai.document.DocumentTransformer;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.util.Assert;
import java.util.List;
import java.util.Map;

@Component
public class KeywordEnricher implements DocumentTransformer {


    public static final String CONTEXT_STR_PLACEHOLDER = "context_str";


    public static final String EXCERPT_KEYWORDS_METADATA_KEY = "category";

    /**
     * Model predictor
     */
    private final ChatClient chatClient;

    /**
     * The prompt template to use for keyword extraction.
     */
    private final PromptTemplate keywordsTemplate;

    public KeywordEnricher(ChatClient.Builder modelBuilder, @Qualifier("chunkingPromptTemplate") PromptTemplate chunkingPromptTemplate) {
        Assert.notNull(chunkingPromptTemplate, "keywordsTemplate must not be null");

        this.chatClient = modelBuilder.build();
        this.keywordsTemplate = chunkingPromptTemplate;
    }

    @Override
    public List<Document> apply(List<Document> documents) {
        for (Document document : documents) {
            assert document.getText() != null;
            Prompt prompt = this.keywordsTemplate.create(Map.of(CONTEXT_STR_PLACEHOLDER, document.getText()));
            String keywords = this.chatClient.prompt(prompt).call().content();
            document.getMetadata().put(EXCERPT_KEYWORDS_METADATA_KEY, keywords);
        }
        return documents;
    }
}
