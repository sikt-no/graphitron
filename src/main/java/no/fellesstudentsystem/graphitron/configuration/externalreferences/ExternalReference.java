package no.fellesstudentsystem.graphitron.configuration.externalreferences;

import org.jetbrains.annotations.NotNull;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

abstract public class ExternalReference<T> {
    protected final Map<String, T> options;

    public ExternalReference(String path) {
        var referenceOptional = findClass(path);
        if (referenceOptional.isEmpty()) {
            options = Map.of();
            return;
        }
        var reference = referenceOptional.get();

        var enumValues = List.of(reference.getEnumConstants());
        var optionsMap = new HashMap<String, T>();
        for (var e : enumValues) {
            var method = getExpectedMethod(e, path);
            optionsMap.put(e.toString(), getTarget(path, e, method));
        }

        options = optionsMap;
    }

    public ExternalReference(Map<String, T> options) {
        this.options = options;
    }

    @NotNull
    private Optional<Class<?>> findClass(String path) {
        if (path == null || path.isEmpty()) {
            return Optional.empty();
        }

        Class<?> reference;
        try {
            reference = Class.forName(path);
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException("Could not load external class at path " + path);
        }

        if (!reference.isEnum()) {
            throw new IllegalArgumentException("Class at path " + path + " is not an enum, but an enum is required here.");
        }

        return Optional.of(reference);
    }

    @NotNull
    private Method getExpectedMethod(Object methodContainer, String path) {
        try {
            return methodContainer.getClass().getDeclaredMethod(getExpectedMethodName());
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException("No method " + getExpectedMethodName() + " found for class at path " + path);
        }
    }

    @NotNull
    private T getTarget(String path, Object enumValue, Method m) {
        try {
            return (T) m.invoke(enumValue);
        } catch (Exception e) {
            throw new RuntimeException("Method " + getExpectedMethodName() + " could not be called for class at path " + path);
        }
    }

    public T get(String name) {
        return options.get(name);
    }

    public boolean contains(String name) {
        return options.containsKey(name);
    }

    abstract protected String getExpectedMethodName();
}
