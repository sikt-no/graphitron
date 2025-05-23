package no.sikt.graphitron.configuration;

import java.lang.reflect.InvocationTargetException;

public class ExtensionInstance<T> {
    private final Class<T> extension;

    public ExtensionInstance(String extension) {
        try {
            this.extension = (Class<T>) Class.forName(extension);
        } catch (ClassNotFoundException e) {
            throw new RuntimeException("Extension class not found: " + extension, e);
        }
    }

    /**
     * Create an instance of a class with its constructor parameters.
     *
     * @param parameterTypes The parameter types of the class constructor.
     * @param initargs The arguments for the class constructor.
     * @throws RuntimeException if the instantiation fails.
     */
    public T createInstance(Class<?>[] parameterTypes, Object ... initargs) {
        try {
            return extension.getDeclaredConstructor(parameterTypes).newInstance(initargs);
        } catch (InstantiationException | IllegalAccessException | InvocationTargetException | NoSuchMethodException e) {
            throw new RuntimeException("An error occurred while instantiating the class", e);
        }
    }
}
