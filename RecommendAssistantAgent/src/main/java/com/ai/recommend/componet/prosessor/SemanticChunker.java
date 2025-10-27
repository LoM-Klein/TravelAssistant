package com.ai.recommend.componet.prosessor;


import com.ai.recommend.model.ClassifiedChunks;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;


import java.util.List;
import java.util.Map;

@Component
public class SemanticChunker {

    private final ChatClient chatClient;
    // 注入一个专用于切块/分类的 PromptTemplate
    private final PromptTemplate chunkingPromptTemplate;
    private final BeanOutputConverter<ClassifiedChunks> outputConverter;

    public SemanticChunker(
            ChatClient.Builder modelBuilder, // 可以使用 builder 创建一个临时的 ChatClient
            @Qualifier("chunkingPromptTemplate") PromptTemplate chunkingPromptTemplate) {

        // 通常 ChatClient Builder 在配置时只需要注入 ChatModel
        // 这里假设您使用了一个专用于结构化输出的 ChatClient 实例
        this.chatClient = modelBuilder.build();
        this.outputConverter = new BeanOutputConverter<>(ClassifiedChunks.class);
        this.chunkingPromptTemplate = chunkingPromptTemplate;
    }


    /**
     * 调用 LLM 进行语义切块和分类
     * @param rawText 原始攻略文本
     * @return 结构化的 ClassifiedChunks 对象
     */
    public ClassifiedChunks chunkAndClassify(List<Document> rawText) {

        // 1. 获取 JSON 格式指令 (LLM 需要知道如何输出 JSON)
        String format = outputConverter.getFormat();

        // 2. 创建 Prompt，注意变量名要与模板中的占位符匹配
        Prompt prompt = chunkingPromptTemplate.create(Map.of("format", format, "raw_guide_text", rawText));
        
        // 3. 使用高级、流畅的 ChatClient API
        return chatClient.prompt(prompt)
                .call()
                // 4. 直接请求解析为目标 Bean/Record 类型
                .entity(ClassifiedChunks.class);
    }
}
