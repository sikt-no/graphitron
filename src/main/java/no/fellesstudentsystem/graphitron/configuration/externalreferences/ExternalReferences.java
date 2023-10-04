package no.fellesstudentsystem.graphitron.configuration.externalreferences;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class ExternalReferences {
    private final Map<String, Class<?>> classes;

    public ExternalReferences(List<ExternalClassReference> references) {
        if (references != null) {
            classes = references
                    .stream()
                    .collect(Collectors.toMap(ExternalClassReference::getName, it -> getClassFromPath(it.getClassPath())));
        } else {
            classes = Map.of();
        }
    }

    private static Class<?> getClassFromPath(String path) {
        try {
            return Class.forName(path);
        } catch (ClassNotFoundException e) {
            throw new IllegalArgumentException("Could not find external class. ", e);
        }
    }

    public boolean contains(String schemaName) {
        return classes.containsKey(schemaName);
    }

    public Class<?> getClassFrom(String schemaName) {
        return classes.get(schemaName);
    }

    public Class<?> getClassFrom(CodeReference reference) {
        return classes.get(reference.getSchemaClassReference());
    }

    /**
     * @return The method this reference points to if it exists.
     * @throws IllegalArgumentException If method does not exist.
     */
    public Method getMethodFrom(CodeReference reference) {
        return getMethodFrom(reference.getSchemaClassReference(), reference.getMethodName());
    }

    /**
     * @return The method this reference points to if it exists.
     * @throws IllegalArgumentException If method does not exist.
     */
    public Method getMethodFrom(String schemaName, String methodName) {
        var classReference = classes.get(schemaName);
        if (classReference == null) {
            throw new IllegalArgumentException("Could not find external class with name " + schemaName);
        }
        return Stream
                .of(classReference.getMethods())
                .filter(it -> it.getName().equalsIgnoreCase(methodName))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Could not find method with name " + methodName + " in external class " + classReference.getName()));
    }
}
