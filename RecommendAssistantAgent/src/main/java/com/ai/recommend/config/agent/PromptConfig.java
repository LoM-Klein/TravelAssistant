package com.ai.recommend.config.agent;


import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;


@Configuration
public class PromptConfig {

    // 注入 RAG 响应模板文件资源
    @Value("classpath:/prompts/response.st")
    private Resource summaryResource;

    // 假设您可能还需要一个用于文档摘要的模板
    @Value("classpath:/prompts/system-qa.st")
    private Resource systemResource;
    @Value("classpath:/prompts/chunkingPromp.st")
    private Resource chunkingPromptTemplate;
    /**
     * 将 RAG 响应模板初始化为一个 Spring Bean
     */
    @Bean
    public PromptTemplate systemPromptTemplate() {
        // 直接使用 Resource 构造函数，让 Spring AI 负责读取文件内容
        return new PromptTemplate(systemResource);
    }

    @Bean
    public PromptTemplate chunkingPromptTemplate() {
        return new PromptTemplate(chunkingPromptTemplate);
    }
    /**
     * 将摘要模板初始化为一个 Spring Bean
     */
    @Bean
    public PromptTemplate summaryPromptTemplate() {
        return new PromptTemplate(summaryResource);
    }
}
