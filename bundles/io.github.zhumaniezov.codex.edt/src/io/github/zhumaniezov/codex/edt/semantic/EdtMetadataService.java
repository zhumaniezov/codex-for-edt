package io.github.zhumaniezov.codex.edt.semantic;

import static io.github.zhumaniezov.codex.edt.protocol.CodexProtocol.object;
import static io.github.zhumaniezov.codex.edt.semantic.MetadataPlan.*;
import java.util.*;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.emf.ecore.*;
import com._1c.g5.v8.bm.core.*;
import com._1c.g5.v8.dt.core.platform.*;
import com._1c.g5.v8.dt.metadata.mdclass.*;
import com._1c.g5.v8.dt.mcore.TypeDescription;
import com.google.gson.*;

/**
 * Операции модели выполняются только внутри задач публичного integration layer.
 */
public final class EdtMetadataService implements AutoCloseable {
    private final EdtServices services = new EdtServices();
    private final EdtTypeService types = new EdtTypeService();
    private final IProject project;

    public EdtMetadataService(IProject project) {
        this.project = Objects.requireNonNull(project);
    }

    private IConfigurationProject configurationProject() {
        if (!project.isOpen()
                || !(services.projects().getProject(project) instanceof IConfigurationProject configuration)) {
            throw new IllegalStateException("EDT configuration project unavailable");
        }
        return configuration;
    }

    public JsonObject read(String tool, JsonObject input) {
        var v8 = configurationProject();
        return services.models().executeReadOnlyTask(tx -> {
            var configuration = tx.toTransactionObject(v8.getConfiguration());
            if (tool.equals("edt_get_project_context")) {
                return object("project", project.getName(), "cwd",
                        io.github.zhumaniezov.codex.edt.context.ProjectContextResolver.directory(project),
                        "configuration", configuration.getName(), "platformModelVersion", v8.getVersion().toString());
            }
            if (tool.equals("edt_get_configuration_info")) {
                return object("name", configuration.getName(), "catalogs", configuration.getCatalogs().size(),
                        "commonModules", configuration.getCommonModules().size(), "synonym",
                        configuration.getSynonym().map());
            }
            var objects = objects(configuration);
            String kind = text(input, "kind"), name = text(input, "name");
            if (!kind.isBlank()) {
                objects.removeIf(o -> !o.eClass().getName().equalsIgnoreCase(kind));
            }
            if (!name.isBlank()) {
                objects.removeIf(o -> !o.getName().equalsIgnoreCase(name));
            }
            if (tool.equals("edt_get_metadata_object") || tool.equals("edt_find_metadata_object")) {
                var found = new JsonArray();
                objects.stream().limit(20).forEach(o -> found.add(locations(o, describe(o, true))));
                return object("status", found.isEmpty() ? "notFound" : "found", "objects", found,
                        "total", objects.size(), "truncated", objects.size() > found.size());
            }
            if (!tool.equals("edt_list_metadata_objects")) {
                throw new IllegalArgumentException("Unknown EDT tool");
            }
            int offset = input.has("offset") ? Math.max(0, input.get("offset").getAsInt()) : 0;
            var values = new JsonArray();
            objects.stream().skip(offset).limit(100).forEach(o -> values.add(describe(o, false)));
            return object("objects", values, "total", objects.size(), "nextOffset",
                    offset + values.size() < objects.size() ? offset + values.size() : null);
        });
    }

    public JsonObject apply(MetadataPlan plan) throws Exception {
        return apply(plan, () -> true);
    }

    public JsonObject apply(MetadataPlan plan, java.util.function.BooleanSupplier valid) throws Exception {
        var v8 = configurationProject();
        var manager = services.models();
        var namespace = manager.getBmNamespace(project);
        var input = plan.json();
        var editing = manager.createLocalEditingContext("Codex metadata plan");
        JsonObject result;
        try {
            result = editing.execute("Codex: metadata", UUID.randomUUID(), EdtMetadataService.class.getName(), tx -> {
                var configuration = tx.toTransactionObject(v8.getConfiguration());
                var results = new JsonArray();
                for (var entry : input.getAsJsonArray("operations")) {
                    if (!valid.getAsBoolean()) {
                        throw new java.util.concurrent.CancellationException("EDT turn expired");
                    }
                    results.add(applyOperation(entry.getAsJsonObject(), configuration, v8, namespace, tx));
                }
                verifyBoundary(configuration);
                for (var entry : results) {
                    var value = entry.getAsJsonObject();
                    if (value.has("object")) {
                        var dto = value.getAsJsonObject("object");
                        var target = objects(configuration).stream()
                                .filter(o -> o.eClass().getName().equals(text(dto, "kind"))
                                        && o.getName().equals(text(dto, "name")))
                                .findFirst();
                        if (target.isPresent()) {
                            verifyBoundary(target.get());
                            locations(target.get(), dto);
                        }
                    }
                }
                if (!valid.getAsBoolean()) {
                    throw new java.util.concurrent.CancellationException("EDT turn expired");
                }
                return object("status", "completed", "results", results);
            });
            editing.save(true);
        } finally {
            editing.dispose();
        }
        manager.waitAllEnqueuedEventsSent();
        Job.getJobManager().join(IBmModelManager.SAVE_JOB_FAMILY, new NullProgressMonitor());
        manager.waitModelSynchronization(project);
        result.add("diagnostics", EdtValidationService.problems(project));
        return result;
    }

    private JsonObject applyOperation(JsonObject op, Configuration configuration, IConfigurationProject v8,
            IBmNamespace namespace, IBmPlatformTransaction tx) {
        String action = text(op, "operation"), name = text(op, "name");
        if (action.equals("createCatalog") || action.equals("createCommonModule")) {
            EClass kind = action.equals("createCatalog") ? MdClassPackage.Literals.CATALOG
                    : MdClassPackage.Literals.COMMON_MODULE;
            MdObject existing = objects(configuration).stream()
                    .filter(o -> o.eClass() == kind && o.getName().equalsIgnoreCase(name)).findFirst().orElse(null);
            if (existing != null) {
                return object("status", "alreadyExists", "object", describe(existing, true));
            }
            MdObject created = services.factory().create(kind, v8);
            initialize(created, op);
            tx.attachTopObject(namespace, (IBmObject) created,
                    services.names().generateStandaloneObjectFqn(kind, name));
            if (created instanceof Catalog catalog) {
                configuration.getCatalogs().add(catalog);
            } else {
                configuration.getCommonModules().add((CommonModule) created);
            }
            properties(created, op);
            if (created instanceof Catalog catalog) {
                addAttributes(catalog, op, v8, configuration);
                if (op.has("tabularSections")) {
                    for (var value : op.getAsJsonArray("tabularSections")) {
                        section(catalog, value.getAsJsonObject(), v8, configuration);
                    }
                }
            }
            attachModules(created, namespace, tx);
            return object("status", "created", "object", describe(created, true));
        }
        if (action.equals("setProperties")) {
            String kind = text(op, "objectKind");
            MdObject target = objects(configuration).stream()
                    .filter(o -> o.getName().equalsIgnoreCase(name) && o.eClass().getName().equals(kind)).findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("Object not found: " + name));
            verifyBoundary(target);
            properties(target, op);
            if (op.has("synonym")) {
                target.getSynonym().put("ru", text(op, "synonym"));
            }
            return object("status", "updated", "object", describe(target, true));
        }
        Catalog catalog = configuration.getCatalogs().stream()
                .filter(o -> o.getName().equalsIgnoreCase(text(op, "catalog"))).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Catalog not found: " + text(op, "catalog")));
        verifyBoundary(catalog);
        if (action.equals("addTabularSection")) {
            var existing = catalog.getTabularSections().stream().filter(s -> s.getName().equalsIgnoreCase(name))
                    .findFirst();
            if (existing.isPresent()) {
                return object("status", "alreadyExists", "object", describe(existing.get(), true));
            }
            return object("status", "created", "object", describe(section(catalog, op, v8, configuration), true));
        }
        EObject parent = catalog;
        if (!text(op, "tabularSection").isBlank()) {
            parent = catalog.getTabularSections().stream()
                    .filter(s -> s.getName().equalsIgnoreCase(text(op, "tabularSection"))).findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("Tabular section not found"));
        }
        return attribute(parent, op, v8, configuration);
    }

    private MdObject section(Catalog parent, JsonObject op, IConfigurationProject v8, Configuration configuration) {
        var existing = parent.getTabularSections().stream().filter(s -> s.getName().equalsIgnoreCase(text(op, "name")))
                .findFirst();
        if (existing.isPresent()) {
            throw new IllegalArgumentException("Tabular section already exists: " + text(op, "name"));
        }
        CatalogTabularSection section = services.factory().create(MdClassPackage.Literals.CATALOG_TABULAR_SECTION,
                parent, v8.getVersion());
        initialize(section, op);
        parent.getTabularSections().add(section);
        addAttributes(section, op, v8, configuration);
        return section;
    }

    private void addAttributes(EObject parent, JsonObject op, IConfigurationProject v8, Configuration configuration) {
        if (op.has("attributes")) {
            for (var value : op.getAsJsonArray("attributes")) {
                attribute(parent, value.getAsJsonObject(), v8, configuration);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private JsonObject attribute(EObject parent, JsonObject op, IConfigurationProject v8, Configuration configuration) {
        var feature = (EReference) parent.eClass().getEStructuralFeature("attributes");
        var attributes = (List<MdObject>) parent.eGet(feature);
        var existing = attributes.stream().filter(a -> a.getName().equalsIgnoreCase(text(op, "name"))).findFirst();
        if (existing.isPresent()) {
            return object("status", "alreadyExists", "object", describe(existing.get(), true));
        }
        MdObject created = services.factory().create(feature.getEReferenceType(), parent, v8.getVersion());
        initialize(created, op);
        created.eSet(created.eClass().getEStructuralFeature("type"),
                types.create(op.getAsJsonObject("type"), v8.getVersion(), name -> {
                    var catalog = configuration.getCatalogs().stream().filter(c -> c.getName().equalsIgnoreCase(name))
                            .findFirst()
                            .orElseThrow(() -> new IllegalArgumentException("CatalogRef not found: " + name));
                    return catalog.getProducedTypes() == null || catalog.getProducedTypes().getRefType() == null ? null
                            : catalog.getProducedTypes().getRefType().getType();
                }));
        attributes.add(created);
        return object("status", "created", "object", describe(created, true));
    }

    private static void initialize(MdObject value, JsonObject op) {
        if (value == null) {
            throw new IllegalStateException("EDT initializer returned null");
        }
        value.setName(name(text(op, "name")));
        if (value.getUuid() == null) {
            value.setUuid(UUID.randomUUID());
        }
        if (op.has("synonym")) {
            value.getSynonym().put("ru", text(op, "synonym"));
        }
    }

    private static void properties(MdObject target, JsonObject op) {
        if (!op.has("properties")) {
            return;
        }
        Set<String> allowed = target instanceof CommonModule
                ? Set.of("clientManagedApplication", "clientOrdinaryApplication", "server", "externalConnection",
                        "serverCall", "global", "privileged")
                : target instanceof Catalog
                        ? Set.of("hierarchical", "codeLength", "descriptionLength", "autonumbering", "checkUnique")
                        : Set.of();
        for (var property : op.getAsJsonObject("properties").entrySet()) {
            String key = property.getKey();
            if (!allowed.contains(key)) {
                throw new IllegalArgumentException("Unsupported property: " + key);
            }
            var feature = target.eClass().getEStructuralFeature(key);
            if (!(feature instanceof EAttribute) || !feature.isChangeable() || feature.isMany()) {
                throw new IllegalArgumentException("Unavailable property: " + key);
            }
            if (feature.getEType() == EcorePackage.Literals.EBOOLEAN) {
                if (!property.getValue().isJsonPrimitive() || !property.getValue().getAsJsonPrimitive().isBoolean()) {
                    throw new IllegalArgumentException(key + ": boolean");
                }
                target.eSet(feature, property.getValue().getAsBoolean());
            } else if (feature.getEType() == EcorePackage.Literals.EINT) {
                int value = property.getValue().getAsBigDecimal().intValueExact();
                if (value < 0 || value > (key.equals("codeLength") ? 9 : 150)) {
                    throw new IllegalArgumentException(key + ": range");
                }
                target.eSet(feature, value);
            } else {
                throw new IllegalArgumentException("Unsupported property type");
            }
        }
    }

    private void attachModules(MdObject value, IBmNamespace namespace, IBmPlatformTransaction tx) {
        for (var feature : value.eClass().getEAllReferences()) {
            if (!feature.isMany() && feature.getEReferenceType().getName().equals("Module")) {
                Object module = value.eGet(feature);
                if (module instanceof IBmObject bm && bm.bmIsTransient()) {
                    tx.attachTopObject(namespace, bm, services.names().generateExternalPropertyFqn(value, feature));
                }
            }
        }
    }

    private org.eclipse.core.resources.IFile verifyBoundary(MdObject value) {
        var file = services.resources().getPlatformResource(value);
        if (file == null || !project.equals(file.getProject()) || file.getLocationURI() == null
                || file.isLinked(org.eclipse.core.resources.IResource.CHECK_ANCESTORS)) {
            throw new IllegalStateException("EDT resource is outside the bound project");
        }
        try {
            var root = java.nio.file.Path.of(project.getLocationURI()).toRealPath();
            var path = java.nio.file.Path.of(file.getLocationURI()).toAbsolutePath().normalize();
            if (!path.startsWith(root)
                    || !io.github.zhumaniezov.codex.edt.client.AgentPolicy.inside(root, path.toString())) {
                throw new IllegalStateException("EDT resource escapes the physical project root");
            }
        } catch (java.io.IOException error) {
            throw new IllegalStateException("EDT project path unavailable", error);
        }
        return file;
    }

    private JsonObject locations(MdObject value, JsonObject dto) {
        var file = services.resources().getPlatformResource(value);
        if (file != null && project.equals(file.getProject())) {
            dto.addProperty("resource", file.getProjectRelativePath().toPortableString());
        }
        if (value instanceof CommonModule) {
            var support = services.files().getProjectFileSystemSupport(project);
            var module = support.getFile(value, MdClassPackage.Literals.COMMON_MODULE__MODULE);
            if (module != null && project.equals(module.getProject())) {
                dto.addProperty("modulePath", module.getProjectRelativePath().toPortableString());
            }
        }
        return dto;
    }

    private static ArrayList<MdObject> objects(Configuration configuration) {
        var result = new ArrayList<MdObject>();
        for (var feature : configuration.eClass().getEAllReferences()) {
            if (feature.isMany() && MdClassPackage.Literals.MD_OBJECT.isSuperTypeOf(feature.getEReferenceType())) {
                for (Object value : (Collection<?>) configuration.eGet(feature)) {
                    if (value instanceof MdObject md) {
                        result.add(md);
                    }
                }
            }
        }
        return result;
    }

    public static JsonObject describe(MdObject value, boolean details) {
        var result = object("kind", value.eClass().getName(), "name", value.getName(), "uuid", value.getUuid(),
                "synonym", value.getSynonym().map());
        if (value instanceof IBmObject bm && !bm.bmIsTransient()) {
            result.addProperty("fqn", bm.bmGetTopObject().bmGetFqn());
        }
        if (!details) {
            return result;
        }
        var properties = new JsonObject();
        for (var feature : value.eClass().getEAllAttributes()) {
            if (feature.isMany() || feature.isDerived()) {
                continue;
            }
            Object data = value.eGet(feature);
            if (data instanceof Boolean b) {
                properties.addProperty(feature.getName(), b);
            } else if (data instanceof Number n) {
                properties.addProperty(feature.getName(), n);
            } else if (data instanceof org.eclipse.emf.common.util.Enumerator e) {
                properties.addProperty(feature.getName(), e.getLiteral());
            }
        }
        result.add("properties", properties);
        for (String name : List.of("attributes", "tabularSections", "type")) {
            var feature = value.eClass().getEStructuralFeature(name);
            if (feature == null) {
                continue;
            }
            Object data = value.eGet(feature);
            if (data instanceof TypeDescription type) {
                result.add("type", EdtTypeService.describe(type));
            } else if (data instanceof Collection<?> items) {
                var values = new JsonArray();
                items.stream().filter(MdObject.class::isInstance).limit(200).map(MdObject.class::cast)
                        .forEach(v -> values.add(describe(v, true)));
                result.add(name, values);
                if (items.size() > 200) {
                    result.addProperty(name + "Truncated", true);
                }
            }
        }
        return result;
    }

    @Override
    public void close() {
        services.close();
    }
}
