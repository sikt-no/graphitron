package no.sikt.graphitron.configuration.externalreferences;

import no.sikt.graphitron.configuration.GeneratorConfig;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

public class ExternalReferences {
    private final Map<String, Class<?>> classes;

    public ExternalReferences(List<? extends ExternalReference> references) {
        if (references != null) {
            classes = references
                    .stream()
                    .collect(Collectors.toMap(ExternalReference::name, ExternalReference::classReference));
        } else {
            classes = Map.of();
        }
    }

    public boolean contains(CodeReference reference) {
        try {
            return getClassFrom(reference) != null;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /**
     * @return The methods this reference points to.
     */
    public List<Method> getMethodsFrom(CodeReference reference) {
        return Arrays
                .stream(getClassFrom(reference).getMethods())
                .filter(it -> it.getName().equalsIgnoreCase(reference.getMethodName()))
                .collect(Collectors.toList());
    }

    public Class<?> getClassFrom(CodeReference reference) {
        var className = reference.getClassName();
        if (className != null) {
            var cls = resolve(className);
            if (cls != null) {
                return cls;
            }

            var resolved = GeneratorConfig.getExternalReferenceImports().stream()
                    .map(pkg -> pkg + "." + className)
                    .map(this::resolve)
                    .filter(Objects::nonNull)
                    .collect(Collectors.toList());

            if (resolved.isEmpty()) {
                throw new IllegalArgumentException(
                        "Could not find external class " + className + " in externalReferenceImports.");
            }

            if (resolved.size() > 1) {
                throw new IllegalArgumentException(
                        className + " resolves to more than one class: " + resolved.stream().map(Class::getName).collect(Collectors.joining(", ")));
            }

            return resolved.get(0);
        }

        var name = reference.getSchemaClassReference();
        return classes.get(name);
    }

    private Class<?> resolve(String className) {
        try {
            return Class.forName(className);
        } catch (ClassNotFoundException e) {
            return null;
        }
    }
}
