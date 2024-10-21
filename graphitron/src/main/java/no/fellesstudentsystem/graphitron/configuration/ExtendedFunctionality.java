package no.fellesstudentsystem.graphitron.configuration;

import java.lang.reflect.InvocationTargetException;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class ExtendedFunctionality {
    private final Map<String, ExtensionInstance<?>> extendedClasses;

    public ExtendedFunctionality(List<Extension> extendedClasses) {
        this.extendedClasses = extendedClasses
                .stream()
                .collect(Collectors.toMap(Extension::getExtendedClass, it -> new ExtensionInstance<>(it.getExtensionClass())));
    }

    public boolean isExtended(Class<?> extendable) {
        return extendedClasses.containsKey(extendable.getName());
    }

    public <T> T createExtensionIfAvailable(Class<T> extendable, Class<?>[] parameterTypes, Object ... params) {
        if (isExtended(extendable)) {
            return (T) extendedClasses.get(extendable.getName()).createInstance(parameterTypes, params);
        } else {
            try {
                return extendable.getDeclaredConstructor(parameterTypes).newInstance(params);
            } catch (InstantiationException | IllegalAccessException | InvocationTargetException | NoSuchMethodException e) {
                throw new RuntimeException("An error occurred while instantiating the class", e);
            }
        }
    }
}
