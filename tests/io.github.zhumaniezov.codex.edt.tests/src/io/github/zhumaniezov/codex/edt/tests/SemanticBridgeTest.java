package io.github.zhumaniezov.codex.edt.tests;

import static org.junit.Assert.*;
import static io.github.zhumaniezov.codex.edt.protocol.CodexProtocol.*;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import org.junit.Test;
import io.github.zhumaniezov.codex.edt.semantic.LocalMcpBridge;

public class SemanticBridgeTest {
    private static HttpResponse<String> post(LocalMcpBridge bridge, String route, String auth, String origin,
            String body) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create(bridge.url(route))).timeout(Duration.ofSeconds(5))
                .header("Content-Type", "application/json");
        if (auth != null) {
            builder.header("Authorization", "Bearer " + auth);
        }
        if (origin != null) {
            builder.header("Origin", origin);
        }
        return HttpClient.newHttpClient().send(builder.POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static String secret(LocalMcpBridge b) {
        return b.environment().values().iterator().next();
    }

    @Test
    public void authenticationAndOriginAreRequired() throws Exception {
        try (var bridge = new LocalMcpBridge()) {
            var route = bridge.register((name, input) -> object("ok", true));
            assertEquals(401, post(bridge, route, null, null, "{}").statusCode());
            assertEquals(401, post(bridge, route, "wrong", null, "{}").statusCode());
            assertEquals(403, post(bridge, route, secret(bridge), "https://example.invalid", "{}").statusCode());
            assertFalse(bridge.config(route).toString().contains(secret(bridge)));
        }
    }

    @Test
    public void initializeAndToolsListUseStableMcp() throws Exception {
        try (var bridge = new LocalMcpBridge()) {
            var route = bridge.register((name, input) -> object());
            var response = post(bridge, route, secret(bridge), null,
                    object("jsonrpc", "2.0", "id", 7, "method", "initialize", "params", object()).toString());
            assertEquals(200, response.statusCode());
            assertEquals("2025-06-18", string(parse(response.body()).getAsJsonObject("result"), "protocolVersion"));
            var list = post(bridge, route, secret(bridge), null,
                    object("jsonrpc", "2.0", "id", 8, "method", "tools/list").toString());
            assertEquals(6, parse(list.body()).getAsJsonObject("result").getAsJsonArray("tools").size());
        }
    }

    @Test
    public void toolResponseCorrelatesIdsAndErrors() throws Exception {
        try (var bridge = new LocalMcpBridge()) {
            var route = bridge.register((name, input) -> {
                throw new IllegalStateException("read only");
            });
            var response = post(bridge, route, secret(bridge), null, object("jsonrpc", "2.0", "id", "request-A",
                    "method", "tools/call", "params", object("name", "edt_apply_metadata_plan")).toString());
            var json = parse(response.body());
            assertEquals("request-A", string(json, "id"));
            assertTrue(json.getAsJsonObject("result").get("isError").getAsBoolean());
        }
    }

    @Test
    public void malformedAndUnregisteredRoutesFailClosed() throws Exception {
        try (var bridge = new LocalMcpBridge()) {
            var route = bridge.register((name, input) -> object());
            assertEquals(400, post(bridge, route, secret(bridge), null, "{oops").statusCode());
            bridge.unregister(route);
            assertEquals(404, post(bridge, route, secret(bridge), null, "{}").statusCode());
        }
    }

    @Test
    public void shutdownClosesOnlyOwnedPort() throws Exception {
        var first = new LocalMcpBridge();
        try (var second = new LocalMcpBridge()) {
            var route = first.register((n, p) -> object());
            var uri = URI.create(first.url(route));
            first.close();
            try (var socket = new java.net.Socket()) {
                assertThrows(java.io.IOException.class,
                        () -> socket.connect(new java.net.InetSocketAddress(uri.getHost(), uri.getPort()), 1000));
            }
            var secondRoute = second.register((n, p) -> object());
            assertEquals(202, post(second, secondRoute, secret(second), null,
                    object("jsonrpc", "2.0", "method", "notifications/initialized").toString()).statusCode());
        }
    }
}
