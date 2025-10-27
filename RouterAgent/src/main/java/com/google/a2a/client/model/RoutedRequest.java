package com.google.a2a.client.model;

/**
 * 路由请求
 * <p>
 * 用户发送给路由 Agent 的请求
 *
 * @param requestId 请求 ID
 * @param userId 用户 ID
 * @param query 用户查询
 * @param contextId 会话上下文 ID
 */
public record RoutedRequest(
    String requestId,
    String userId,
    String query,
    String contextId
) {
    public static RoutedRequest create(String userId, String query, String contextId) {
        return new RoutedRequest(
            java.util.UUID.randomUUID().toString(),
            userId,
            query,
            contextId
        );
    }
}

