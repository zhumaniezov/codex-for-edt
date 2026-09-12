package io.github.zhumaniezov.codex.edt.tests;

import static org.junit.Assert.*;
import static io.github.zhumaniezov.codex.edt.protocol.CodexProtocol.*;
import java.util.concurrent.CompletableFuture;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.ui.PlatformUI;
import com.google.gson.JsonParser;
import io.github.zhumaniezov.codex.edt.semantic.*;

final class SemanticRestartScenario {
    static void run(String phase) throws Exception {
        String name = "codex-semantic-restart";
        var work = CompletableFuture.runAsync(() -> {
            try {
                var project = phase.equals("seed") ? SemanticFixture.create(name)
                        : ResourcesPlugin.getWorkspace().getRoot().getProject(name);
                assertTrue(project.isOpen());
                SemanticFixture.active(project, true);
                try (var metadata = new EdtMetadataService(project)) {
                    if (phase.equals("seed")) {
                        metadata.apply(
                                new MetadataPlan(JsonParser.parseString(SemanticEdtTest.PLAN).getAsJsonObject()));
                    }
                    var catalog = metadata.read("edt_get_metadata_object", object("kind", "Catalog", "name", "Товары"));
                    assertEquals(4, catalog.getAsJsonArray("objects").get(0).getAsJsonObject()
                            .getAsJsonArray("attributes").size());
                    assertEquals("found",
                            metadata.read("edt_find_metadata_object",
                                    object("kind", "CommonModule", "name", "ОбщегоНазначения")).get("status")
                                    .getAsString());
                    assertTrue(project.getFile("src/Catalogs/Товары/Товары.mdo").exists());
                }
            } catch (Exception error) {
                throw new java.util.concurrent.CompletionException(error);
            }
        });
        ViewScenario.waitFor(work::isDone, 90, () -> {
        });
        work.get();
        var page = PlatformUI.getWorkbench().getActiveWorkbenchWindow().getActivePage();
        String id = "io.github.zhumaniezov.codex.edt.views.Codex";
        if (phase.equals("seed")) {
            page.closeAllEditors(false);
            page.showView(id);
        }
        var reference = page.findViewReference(id);
        assertNotNull(reference);
        assertEquals("io.github.zhumaniezov.codex.edt.ui.CodexView", reference.getView(true).getClass().getName());
        assertNull(page.getActiveEditor());
        System.out.println("SEMANTIC_RESTART_PASS " + phase);
    }
}
