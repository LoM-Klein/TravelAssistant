package com.google.a2a.client.config;

import org.springframework.boot.web.server.ErrorPage;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.boot.web.servlet.server.ConfigurableServletWebServerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Web MVC 配置
 * <p>
 * 配置静态资源和路由转发，支持 Vue SPA 应用
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {
    
    /**
     * 配置视图控制器
     * 将根路径转发到 index.html
     */
    @Override
    public void addViewControllers(ViewControllerRegistry registry) {
        // 根路径转发到 index.html
        registry.addViewController("/").setViewName("forward:/index.html");
        
        // 404 错误页面也转发到 index.html，让前端路由处理
        registry.addViewController("/notFound").setViewName("forward:/index.html");
        registry.setOrder(Ordered.HIGHEST_PRECEDENCE);
    }
    
    /**
     * 配置错误页面
     * 所有404错误转发到/notFound，最终由前端路由处理
     * 这样可以支持前端的 History 路由模式
     */
    @Bean
    public WebServerFactoryCustomizer<ConfigurableServletWebServerFactory> webServerFactoryCustomizer() {
        return factory -> {
            ErrorPage errorPage = new ErrorPage(HttpStatus.NOT_FOUND, "/notFound");
            factory.addErrorPages(errorPage);
        };
    }
}

