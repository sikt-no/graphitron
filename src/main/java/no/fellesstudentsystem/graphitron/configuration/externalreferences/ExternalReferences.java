package no.fellesstudentsystem.graphitron.configuration.externalreferences;

import no.fellesstudentsystem.graphitron.configuration.GeneratorConfig;

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
                    .collect(Collectors.toMap(ExternalReference::getName, ExternalReference::getClassReference));
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
     * @return The method this reference points to if it exists.
     * @throws IllegalArgumentException If method does not exist.
     */
    public Method getMethodFrom(CodeReference reference) {
        return getMethodFrom(reference, false);
    }

    /**
     * @return The method this reference points to if it exists.
     * @throws IllegalArgumentException If method does not exist.
     */
    public Method getNullableMethodFrom(CodeReference reference) {
        return getMethodFrom(reference, true);
    }

    /**
     * @return The method this reference points to if it exists.
     * @throws IllegalArgumentException If method does not exist.
     */
    public Method getMethodFrom(CodeReference reference, boolean nullable) {
        var cls = getClassFrom(reference);
        var method = Arrays.stream(cls.getMethods())
                .filter(it -> it.getName().equalsIgnoreCase(reference.getMethodName()))
                .findFirst();

        if (method.isPresent()) {
            return method.get();
        }

        if (nullable) {
            return null;
        }

        throw new IllegalArgumentException(cls.getName() + " does not contain method named " + reference.getMethodName());
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
