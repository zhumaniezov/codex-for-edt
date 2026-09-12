package io.github.zhumaniezov.codex.edt.tests;

import static org.junit.Assert.*;
import static io.github.zhumaniezov.codex.edt.protocol.CodexProtocol.*;
import org.junit.Test;
import com.google.gson.*;
import io.github.zhumaniezov.codex.edt.semantic.*;

public class SemanticContractTest {
    private static MetadataPlan plan(String operations) {
        return new MetadataPlan(JsonParser.parseString("{\"operations\":" + operations + "}").getAsJsonObject());
    }

    @Test
    public void groupedPlanIsImmutable() {
        var source = JsonParser.parseString(SemanticEdtTest.PLAN).getAsJsonObject();
        var plan = new MetadataPlan(source);
        source.remove("operations");
        plan.json().remove("operations");
        assertEquals(2, plan.json().getAsJsonArray("operations").size());
    }

    @Test
    public void rejectsEmptyPlanAndUnknownOperations() {
        assertThrows(IllegalArgumentException.class, () -> plan("[]"));
        assertThrows(IllegalArgumentException.class,
                () -> plan("[{\"operation\":\"deleteConfiguration\",\"name\":\"Тест\"}]"));
    }

    @Test
    public void rejectsPathTraversalAsMetadataName() {
        for (String value : new String[] { "../Тест", "A/B", "A\\B", "A.B", "", "1Имя" }) {
            assertThrows(IllegalArgumentException.class, () -> MetadataPlan.name(value));
        }
        assertEquals("Товары_2", MetadataPlan.name("Товары_2"));
    }

    @Test
    public void stringLimits() {
        MetadataPlan.validateType(object("kind", "String", "length", 30));
        assertThrows(IllegalArgumentException.class,
                () -> MetadataPlan.validateType(object("kind", "String", "length", -1)));
        assertThrows(IllegalArgumentException.class,
                () -> MetadataPlan.validateType(object("kind", "String", "length", 1.5)));
    }

    @Test
    public void numberPrecisionAndScale() {
        MetadataPlan.validateType(object("kind", "Number", "precision", 15, "scale", 2));
        assertThrows(IllegalArgumentException.class,
                () -> MetadataPlan.validateType(object("kind", "Number", "precision", 15, "scale", 16)));
    }

    @Test
    public void dateBooleanAndReferenceTypes() {
        MetadataPlan.validateType(object("kind", "Boolean"));
        MetadataPlan.validateType(object("kind", "Date", "fractions", "Date"));
        MetadataPlan.validateType(object("kind", "CatalogRef", "catalog", "Товары"));
        assertThrows(IllegalArgumentException.class,
                () -> MetadataPlan.validateType(object("kind", "Date", "fractions", "Guess")));
        assertThrows(IllegalArgumentException.class, () -> MetadataPlan.validateType(object("kind", "ArbitraryType")));
    }

    @Test
    public void unknownConfigFieldsAreNotForwarded() {
        assertThrows(IllegalArgumentException.class,
                () -> new MetadataPlan(object("operations", new JsonArray(), "cwd", "C:/")));
        assertThrows(IllegalArgumentException.class,
                () -> plan("[{\"operation\":\"createCatalog\",\"name\":\"Товары\",\"shell\":\"run\"}]"));
    }

    @Test
    public void toolSchemasRequireRealTurnCorrelation() {
        var tools = SemanticTools.list().getAsJsonArray("tools");
        assertEquals(6, tools.size());
        for (var tool : tools) {
            var schema = tool.getAsJsonObject().getAsJsonObject("inputSchema");
            assertTrue(schema.getAsJsonArray("required").contains(new JsonPrimitive("turnKey")));
            assertFalse(schema.get("additionalProperties").getAsBoolean());
        }
    }

    @Test
    public void approvalCannotBeAnsweredTwice() {
        var approval = new SemanticApproval("threadA", "turnA", "project",
                new MetadataPlan(JsonParser.parseString(SemanticEdtTest.PLAN).getAsJsonObject()));
        approval.answer(false);
        approval.answer(true);
        assertFalse(approval.decision().join());
        assertEquals("turnA", approval.turn());
        assertEquals("threadA", approval.thread());
    }

    @Test
    public void planPresentationIncludesNestedTypesAndProperties() {
        var text = MetadataPresentation
                .plan(new MetadataPlan(JsonParser.parseString(SemanticEdtTest.PLAN).getAsJsonObject()));
        for (String expected : new String[] { "Товары", "Артикул", "30", "ДополнительныеКоды", "Код", "50", "server",
                "true" }) {
            assertTrue(expected, text.contains(expected));
        }
    }
}
