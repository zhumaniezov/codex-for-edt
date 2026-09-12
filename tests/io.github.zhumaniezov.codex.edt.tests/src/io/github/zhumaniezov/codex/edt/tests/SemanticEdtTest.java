package io.github.zhumaniezov.codex.edt.tests;

import static org.junit.Assert.*;
import com.google.gson.*;
import org.junit.Test;
import org.junit.Assume;
import org.eclipse.core.runtime.Platform;
import org.eclipse.core.runtime.NullProgressMonitor;
import com._1c.g5.wiring.ServiceAccess;
import com._1c.g5.v8.dt.core.model.IModelObjectFactory;
import com._1c.g5.v8.dt.core.platform.IConfigurationProjectManager;
import com._1c.g5.v8.dt.metadata.mdclass.*;
import com._1c.g5.v8.dt.platform.version.Version;
import io.github.zhumaniezov.codex.edt.semantic.*;

public class SemanticEdtTest {
    public static final String PLAN = """
            {"operations":[{"operation":"createCatalog","name":"Товары","attributes":[
              {"name":"Артикул","type":{"kind":"String","length":30}},
              {"name":"Цена","type":{"kind":"Number","precision":15,"scale":2}},
              {"name":"Активен","type":{"kind":"Boolean"}},
              {"name":"Дата","type":{"kind":"Date","fractions":"DateTime"}}],
              "tabularSections":[{"name":"ДополнительныеКоды","attributes":[{"name":"Код","type":{"kind":"String","length":50}}]}]},
              {"operation":"createCommonModule","name":"ОбщегоНазначения","properties":{"server":true,"clientManagedApplication":false}}]}
            """;

    @Test
    public void publicFactoriesCreateAndPersistNativeMetadata() throws Exception {
        Assume.assumeTrue(Platform.getProduct() != null
                && "com._1c.g5.v8.dt.product.application.rcp".equals(Platform.getProduct().getId()));
        var future = java.util.concurrent.CompletableFuture.runAsync(() -> {
            org.eclipse.core.resources.IProject project = null;
            try {
                var factory = ServiceAccess.get(IModelObjectFactory.class, "service.name", "MdObjectFactory");
                Configuration configuration = factory.create(MdClassPackage.Literals.CONFIGURATION, Version.V8_3_27);
                configuration.setName("ПроверкаСемантики");
                project = ServiceAccess.get(IConfigurationProjectManager.class).create(
                        "codex-semantic-" + java.util.UUID.randomUUID(), Version.V8_3_27, configuration,
                        new NullProgressMonitor());
                System.out.println(
                        "SEMANTIC_NATURES " + java.util.Arrays.toString(project.getDescription().getNatureIds()));
                try (var services = new EdtServices()) {
                    var v8 = (com._1c.g5.v8.dt.core.platform.IConfigurationProject) services.projects()
                            .getProject(project);
                    services.models().executeReadOnlyTask(tx -> {
                        var c = tx.toTransactionObject(v8.getConfiguration());
                        var lookup = ServiceAccess.get(com._1c.g5.v8.dt.core.platform.IResourceLookup.class);
                        System.out.println("SEMANTIC_CONFIG fqn=" + ((com._1c.g5.v8.bm.core.IBmObject) c).bmGetFqn()
                                + " transient=" + ((com._1c.g5.v8.bm.core.IBmObject) c).bmIsTransient() + " path="
                                + lookup.getPlatformResourceUri(c));
                        return null;
                    });
                }
                try (var metadata = new EdtMetadataService(project)) {
                    var result = metadata.apply(new MetadataPlan(JsonParser.parseString(PLAN).getAsJsonObject()));
                    System.out.println("SEMANTIC_RESULT " + result.get("status"));
                    assertEquals("completed", result.get("status").getAsString());
                    var catalog = metadata.read("edt_get_metadata_object",
                            JsonParser.parseString("{\"kind\":\"Catalog\",\"name\":\"Товары\"}").getAsJsonObject());
                    assertEquals(4, catalog.getAsJsonArray("objects").get(0).getAsJsonObject()
                            .getAsJsonArray("attributes").size());
                    project.refreshLocal(org.eclipse.core.resources.IResource.DEPTH_INFINITE,
                            new NullProgressMonitor());
                    assertTrue("catalog resource", project.getFile("src/Catalogs/Товары/Товары.mdo").exists());
                    assertTrue("module resource",
                            project.getFile("src/CommonModules/ОбщегоНазначения/ОбщегоНазначения.mdo").exists());
                    var duplicate = metadata.apply(new MetadataPlan(JsonParser.parseString(PLAN).getAsJsonObject()));
                    assertEquals("alreadyExists",
                            duplicate.getAsJsonArray("results").get(0).getAsJsonObject().get("status").getAsString());
                    try (var resolver = new io.github.zhumaniezov.codex.edt.context.ProjectContextResolver()) {
                        assertEquals(project.getName(), resolver.capture(null, null, "").projectName());
                        assertEquals(project.getName(),
                                resolver.capture(null, null, project.getLocation().toOSString()).projectName());
                    }
                    var invalid = new MetadataPlan(JsonParser
                            .parseString(
                                    """
                                            {"operations":[{"operation":"createCatalog","name":"Откат"},{"operation":"addAttribute","catalog":"Откат","name":"Ссылка","type":{"kind":"CatalogRef","catalog":"НеСуществует"}}]}
                                            """)
                            .getAsJsonObject());
                    assertThrows(Exception.class, () -> metadata.apply(invalid));
                    assertEquals("notFound", metadata
                            .read("edt_find_metadata_object", io.github.zhumaniezov.codex.edt.protocol.CodexProtocol
                                    .object("kind", "Catalog", "name", "Откат"))
                            .get("status").getAsString());
                    assertFalse(project.getFile("src/Catalogs/Откат/Откат.mdo").exists());
                    var extra = new MetadataPlan(JsonParser
                            .parseString(
                                    """
                                            {"operations":[{"operation":"addAttribute","catalog":"Товары","name":"Аналог","type":{"kind":"CatalogRef","catalog":"Товары"}},
                                            {"operation":"addTabularSection","catalog":"Товары","name":"Свойства"},
                                            {"operation":"addAttribute","catalog":"Товары","tabularSection":"Свойства","name":"Значение","type":{"kind":"String","length":20}},
                                            {"operation":"setProperties","objectKind":"CommonModule","name":"ОбщегоНазначения","properties":{"externalConnection":true,"serverCall":true}}]}
                                            """)
                            .getAsJsonObject());
                    metadata.apply(extra);
                    project.close(new NullProgressMonitor());
                    SemanticFixture.active(project, false);
                    project.open(new NullProgressMonitor());
                    SemanticFixture.active(project, true);
                    var restored = metadata.read("edt_get_metadata_object",
                            io.github.zhumaniezov.codex.edt.protocol.CodexProtocol.object("kind", "Catalog", "name",
                                    "Товары"));
                    assertEquals(5, restored.getAsJsonArray("objects").get(0).getAsJsonObject()
                            .getAsJsonArray("attributes").size());
                    assertEquals(2, restored.getAsJsonArray("objects").get(0).getAsJsonObject()
                            .getAsJsonArray("tabularSections").size());
                    verifyBridgePermissions(project, metadata);
                    System.out.println("SEMANTIC_NATIVE_PASS " + project.getName());
                }
            } catch (Exception error) {
                throw new java.util.concurrent.CompletionException(error);
            } finally {
                if (project != null) {
                    SemanticFixture.delete(project);
                }
            }
        });
        ViewScenario.waitFor(future::isDone, 90, () -> {
        });
        future.get();
    }

    private static void verifyBridgePermissions(org.eclipse.core.resources.IProject project,
            EdtMetadataService metadata) throws Exception {
        var approvals = new java.util.concurrent.LinkedBlockingQueue<SemanticApproval>();
        var listener = new io.github.zhumaniezov.codex.edt.client.CodexClient.Listener() {
            public void status(String value) {
            }

            public void disconnected(Throwable error) {
            }

            public void semanticApproval(SemanticApproval value) {
                approvals.add(value);
            }
        };
        try (var session = new SemanticSession(() -> listener)) {
            var config = io.github.zhumaniezov.codex.edt.protocol.CodexProtocol.object("mcp_servers", new JsonObject());
            session.configure(config, java.nio.file.Path.of(project.getLocationURI()).toRealPath());
            var endpoint = config.getAsJsonObject("mcp_servers").getAsJsonObject(session.serverName());
            String key = session.begin("thread-A", io.github.zhumaniezov.codex.edt.client.PermissionMode.READ_ONLY);
            session.bind("turn-A");
            var input = JsonParser
                    .parseString("{\"operations\":[{\"operation\":\"createCatalog\",\"name\":\"Подтверждение\"}]}")
                    .getAsJsonObject();
            input.addProperty("turnKey", key);
            assertTrue(invoke(endpoint, session.environment(), "edt_apply_metadata_plan", input).get("isError")
                    .getAsBoolean());
            session.end();
            assertTrue(invoke(endpoint, session.environment(), "edt_get_configuration_info",
                    io.github.zhumaniezov.codex.edt.protocol.CodexProtocol.object("turnKey", key)).get("isError")
                    .getAsBoolean());
            String next = session.begin("thread-A", io.github.zhumaniezov.codex.edt.client.PermissionMode.STRICT);
            session.bind("turn-B");
            assertTrue(invoke(endpoint, session.environment(), "edt_apply_metadata_plan", input).get("isError")
                    .getAsBoolean());
            input.addProperty("turnKey", next);
            assertTrue("Нет dirty guard — запись запрещена",
                    invoke(endpoint, session.environment(), "edt_apply_metadata_plan", input).get("isError")
                            .getAsBoolean());
            var guard = new java.util.concurrent.atomic.AtomicReference<io.github.zhumaniezov.codex.edt.context.ProjectWriteGuard>();
            var workbench = org.eclipse.ui.PlatformUI.getWorkbench();
            workbench.getDisplay().syncExec(
                    () -> guard.set(io.github.zhumaniezov.codex.edt.context.ProjectWriteGuard.acquire(workbench,
                            workbench.getActiveWorkbenchWindow().getShell(), project.getLocation().toOSString(),
                            session::end)));
            assertNotNull(guard.get());
            try {
                var call = java.util.concurrent.CompletableFuture
                        .supplyAsync(() -> invoke(endpoint, session.environment(), "edt_apply_metadata_plan", input));
                var approval = approvals.poll(10, java.util.concurrent.TimeUnit.SECONDS);
                assertNotNull(approval);
                assertEquals(
                        "notFound", metadata
                                .read("edt_find_metadata_object", io.github.zhumaniezov.codex.edt.protocol.CodexProtocol
                                        .object("kind", "Catalog", "name", "Подтверждение"))
                                .get("status").getAsString());
                approval.answer(false);
                assertTrue(call.get(15, java.util.concurrent.TimeUnit.SECONDS).toString().contains("declined"));
                var accepted = java.util.concurrent.CompletableFuture
                        .supplyAsync(() -> invoke(endpoint, session.environment(), "edt_apply_metadata_plan", input));
                approval = approvals.poll(10, java.util.concurrent.TimeUnit.SECONDS);
                assertNotNull(approval);
                assertEquals("turn-B", approval.turn());
                approval.answer(true);
                assertFalse(accepted.get(40, java.util.concurrent.TimeUnit.SECONDS).get("isError").getAsBoolean());
                assertEquals(
                        "found", metadata
                                .read("edt_find_metadata_object", io.github.zhumaniezov.codex.edt.protocol.CodexProtocol
                                        .object("kind", "Catalog", "name", "Подтверждение"))
                                .get("status").getAsString());
                var cancelled = java.util.concurrent.CompletableFuture
                        .supplyAsync(() -> invoke(endpoint, session.environment(), "edt_apply_metadata_plan", input));
                approval = approvals.poll(10, java.util.concurrent.TimeUnit.SECONDS);
                assertNotNull(approval);
                session.end();
                approval.answer(true);
                assertTrue(cancelled.get(15, java.util.concurrent.TimeUnit.SECONDS).toString().contains("declined"));
            } finally {
                workbench.getDisplay().syncExec(() -> guard.get().close());
            }
        }
    }

    private static JsonObject invoke(JsonObject config, java.util.Map<String, String> environment, String tool,
            JsonObject input) {
        try {
            var request = java.net.http.HttpRequest.newBuilder(java.net.URI.create(config.get("url").getAsString()))
                    .header("Authorization",
                            "Bearer " + environment.get(config.get("bearer_token_env_var").getAsString()))
                    .header("Content-Type", "application/json").timeout(java.time.Duration.ofSeconds(55)).POST(
                            java.net.http.HttpRequest.BodyPublishers
                                    .ofString(
                                            io.github.zhumaniezov.codex.edt.protocol.CodexProtocol
                                                    .object("jsonrpc", "2.0", "id", 1, "method", "tools/call", "params",
                                                            io.github.zhumaniezov.codex.edt.protocol.CodexProtocol
                                                                    .object("name", tool, "arguments", input))
                                                    .toString()))
                    .build();
            var response = java.net.http.HttpClient.newHttpClient().send(request,
                    java.net.http.HttpResponse.BodyHandlers.ofString());
            assertEquals(200, response.statusCode());
            return JsonParser.parseString(response.body()).getAsJsonObject().getAsJsonObject("result");
        } catch (Exception error) {
            throw new java.util.concurrent.CompletionException(error);
        }
    }
}
