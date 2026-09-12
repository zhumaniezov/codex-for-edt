package io.github.zhumaniezov.codex.edt.semantic;

import java.util.Map;
import org.eclipse.emf.ecore.EClass;
import org.eclipse.emf.ecore.EReference;
import org.eclipse.emf.ecore.EAttribute;

/** Проверенное описание владения и доступных операций, а не произвольный EMF setter. */
public record MetadataTypeDescriptor(String name, EClass type, EReference configurationRelation,
        Map<String, EAttribute> properties, Map<String, EReference> children, Map<String, EReference> modules) {
    public MetadataTypeDescriptor {
        properties = Map.copyOf(properties);
        children = Map.copyOf(children);
        modules = Map.copyOf(modules);
    }
}
