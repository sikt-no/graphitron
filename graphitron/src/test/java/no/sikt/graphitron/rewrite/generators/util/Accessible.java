package no.sikt.graphitron.rewrite.generators.util;

import java.lang.reflect.Method;

/**
 * Reflective access to the emitted runtime's package-private surface ({@code PinnedConnection} and
 * {@code GraphitronRuntime.acquire}) from the test package: the harness loads the emitted classes
 * into {@code com.example.schema}, so a test reaches them only through a method made accessible.
 */
final class Accessible {

    private Accessible() {}

    /** {@code type}'s own method {@code name(parameterTypes)}, made accessible. */
    static Method method(Class<?> type, String name, Class<?>... parameterTypes) throws NoSuchMethodException {
        Method method = type.getDeclaredMethod(name, parameterTypes);
        method.setAccessible(true);
        return method;
    }
}
