package com.ai.recommend.registry;

import com.alibaba.cloud.ai.graph.agent.BaseAgent;
import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.alibaba.cloud.ai.graph.exception.GraphStateException;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * 注册 AgentCard 的组件。
 * 使用 @Component 确保 Spring 扫描并管理该类。
 */
@Configuration
public class RegistAgentCard {
    @Bean
    @Primary
    public BaseAgent rootAgent(ChatModel chatModel) throws GraphStateException {
        return ReactAgent.builder().name("{your_agent_name}").description("{your_agent_description}").model(chatModel)
                .instruction("{your_agent_system_prompt").build();
    }

}