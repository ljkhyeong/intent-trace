package io.intenttrace

import io.modelcontextprotocol.client.McpClient
import io.modelcontextprotocol.client.McpSyncClient
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest
import io.modelcontextprotocol.spec.McpSchema.CallToolResult
import org.springframework.http.HttpHeaders
import java.net.http.HttpRequest

/** 실제 포트의 `/mcp`에 [session]으로 인증해 초기화한 MCP Java SDK 클라이언트. 호출한 쪽에서 `use`로 닫는다. */
fun mcpClient(port: Int, session: String): McpSyncClient = McpClient.sync(
    HttpClientStreamableHttpTransport.builder("http://127.0.0.1:$port")
        .requestBuilder(HttpRequest.newBuilder().header(HttpHeaders.AUTHORIZATION, "Bearer $session"))
        .build(),
).build().also { it.initialize() }

fun McpSyncClient.call(tool: String, arguments: Map<String, Any?>): CallToolResult =
    callTool(CallToolRequest(tool, arguments))

/** 도구 결과의 structuredContent. 숫자는 크기에 따라 Integer 또는 Long이므로 [Number]로 비교한다. */
val CallToolResult.structured: Map<*, *> get() = structuredContent() as Map<*, *>
