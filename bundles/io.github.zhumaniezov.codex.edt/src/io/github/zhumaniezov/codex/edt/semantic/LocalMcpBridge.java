package io.github.zhumaniezov.codex.edt.semantic;

import static io.github.zhumaniezov.codex.edt.protocol.CodexProtocol.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import com.google.gson.*;
import org.eclipse.jetty.server.*;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

/**
 * Локальный MCP Streamable HTTP с JSON-ответами; HTTP разбирает штатный Jetty
 * EDT.
 */
public final class LocalMcpBridge implements AutoCloseable {
    @FunctionalInterface
    public interface ToolHandler {
        JsonObject call(String name, JsonObject arguments) throws Exception;
    }

    private final Server server;
    private final ServerConnector connector;
    private final String secret;
    private final String envName = "CODEX_EDT_MCP_" + UUID.randomUUID().toString().replace("-", "");
    private record Route(ToolHandler handler,java.util.function.Supplier<JsonObject> catalog) { }
    private final Map<String, Route> routes = new ConcurrentHashMap<>();

    public LocalMcpBridge() throws Exception {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        secret = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        var pool = new QueuedThreadPool(12, 4, 10000);
        pool.setDaemon(true);
        pool.setName("codex-edt-mcp");
        server = new Server(pool);
        server.setStopTimeout(1500);
        connector = new ServerConnector(server, 1, 1);
        connector.setHost("127.0.0.1");
        connector.setPort(0);
        connector.setIdleTimeout(910000);
        connector.setAcceptQueueSize(8);
        server.addConnector(connector);
        server.setHandler(new AbstractHandler() {
            @Override
            public void handle(String target, Request base, HttpServletRequest request, HttpServletResponse response)
                    throws IOException {
                base.setHandled(true);
                handleRequest(target, request, response);
            }
        });
        try {
            server.start();
        } catch (Exception error) {
            server.stop();
            throw error;
        }
    }

    public Map<String, String> environment() {
        return Map.of(envName, secret);
    }

    public String register(ToolHandler handler) {
        return register(handler,SemanticTools::list);
    }

    public String register(ToolHandler handler,java.util.function.Supplier<JsonObject> catalog) {
        String route = "/mcp/" + UUID.randomUUID();
        routes.put(route, new Route(handler,catalog));
        return route;
    }

    public void unregister(String route) {
        if (route != null) {
            routes.remove(route);
        }
    }

    public String url(String route) {
        return "http://127.0.0.1:" + connector.getLocalPort() + route;
    }

    public JsonObject config(String route) {
        return object("enabled", true, "url", url(route), "bearer_token_env_var", envName, "startup_timeout_sec", 20,
                "tool_timeout_sec", 900);
    }

    private void handleRequest(String target, HttpServletRequest request, HttpServletResponse response)
            throws IOException {
        response.setHeader("Cache-Control", "no-store");
        String origin = request.getHeader("Origin"), host = "127.0.0.1:" + connector.getLocalPort();
        String auth = request.getHeader("Authorization");
        if (!host.equals(request.getHeader("Host")) || (origin != null && !origin.equals("http://" + host))) {
            response.setStatus(403);
            return;
        }
        if (auth == null || !MessageDigest.isEqual(("Bearer " + secret).getBytes(StandardCharsets.UTF_8),
                auth.getBytes(StandardCharsets.UTF_8))) {
            response.setStatus(401);
            return;
        }
        Route route = routes.get(target);
        if (route == null) {
            response.setStatus(404);
            return;
        }
        if (!request.getMethod().equals("POST")) {
            response.setHeader("Allow", "POST");
            response.setStatus(405);
            return;
        }
        if (request.getContentType() == null
                || !request.getContentType().toLowerCase(Locale.ROOT).startsWith("application/json")) {
            response.setStatus(415);
            return;
        }
        String protocol = request.getHeader("MCP-Protocol-Version");
        if (protocol != null && !Set.of("2025-03-26", "2025-06-18", "2025-11-25").contains(protocol)) {
            response.setStatus(400);
            return;
        }
        var bytes = request.getInputStream().readNBytes(512 * 1024 + 1);
        if (bytes.length > 512 * 1024) {
            response.setStatus(413);
            return;
        }
        JsonObject message;
        try {
            message = JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8)).getAsJsonObject();
        } catch (RuntimeException error) {
            response.setStatus(400);
            return;
        }
        if (!"2.0".equals(string(message, "jsonrpc")) || !message.has("method")) {
            response.setStatus(400);
            return;
        }
        if (!message.has("id")) {
            response.setStatus(202);
            return;
        }
        var id = message.get("id");
        if (!id.isJsonPrimitive() || !(id.getAsJsonPrimitive().isString() || id.getAsJsonPrimitive().isNumber())) {
            response.setStatus(400);
            return;
        }
        JsonObject result;
        try {
            var params = message.has("params") ? message.getAsJsonObject("params") : object();
            switch (string(message, "method")) {
            case "initialize" -> result = object("protocolVersion", "2025-06-18", "capabilities",
                    object("tools", object("listChanged", false)), "serverInfo",
                    object("name", "codex-edt-semantic", "version", "0.8.0"), "instructions",
                    "EDT native metadata tools. Use one grouped metadata plan; never generate metadata XML by shell or file patches.");
            case "ping" -> result = object();
            case "tools/list" -> result = route.catalog().get();
            case "tools/call" -> {
                JsonObject value;
                boolean failed = false;
                try {
                    value = route.handler().call(string(params, "name"),
                            params.has("arguments") ? params.getAsJsonObject("arguments") : object());
                } catch (Exception error) {
                    failed = true;
                    value = object("status", "error", "code",error instanceof EdtToolException e ? e.code() : "EDT_OPERATION_FAILED", "message",
                            redact(error.getMessage() == null ? "EDT operation failed" : error.getMessage()));
                }
                result = object("content", List.of(object("type", "text", "text", JSON.toJson(value))), "isError",
                        failed);
            }
            default -> {
                write(response, object("jsonrpc", "2.0", "id", id, "error",
                        object("code", -32601, "message", "Method not found")));
                return;
            }
            }
        } catch (RuntimeException error) {
            write(response,
                    object("jsonrpc", "2.0", "id", id, "error", object("code", -32602, "message", "Invalid params")));
            return;
        }
        write(response, object("jsonrpc", "2.0", "id", id, "result", result));
    }

    private static void write(HttpServletResponse response, JsonObject message) throws IOException {
        byte[] bytes = JSON.toJson(message).getBytes(StandardCharsets.UTF_8);
        response.setStatus(200);
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        response.setContentLength(bytes.length);
        response.getOutputStream().write(bytes);
    }

    @Override
    public void close() {
        routes.clear();
        try {
            server.stop();
            server.destroy();
        } catch (Exception error) {
            throw new IllegalStateException("MCP shutdown failed", error);
        }
    }
}
