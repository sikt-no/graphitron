package no.fellesstudentsystem.graphitron.mappings;

import java.util.Arrays;

/**
 * Helper class that takes care of any reflection operations the code generator might require.
 */
public class ReflectionHelpers {
    /**
     * @return Does this jOOQ table contain this method name?
     */
    public static boolean classHasMethod(Class<?> reference, String methodName) {
        return Arrays.stream(reference.getMethods()).anyMatch(it -> it.getName().equals(methodName));
    }
}
