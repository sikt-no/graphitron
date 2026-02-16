package no.sikt.graphitron.mappings;

import org.jooq.impl.UpdatableRecordImpl;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Optional;

import static org.apache.commons.lang3.StringUtils.capitalize;

/**
 * Helper class that takes care of any reflection operations the code generator might require.
 */
public class ReflectionHelpers {
    /**
     * @return Does this jOOQ table contain this method name?
     */
    public static boolean classHasMethod(Class<?> reference, String methodName) {
        return reference != null && Arrays.stream(reference.getMethods()).anyMatch(it -> it.getName().equals(methodName));
    }

    /**
     * Gets the jOOQ record class for a field in a Java record class.
     * @param recordClass The Java record class to inspect
     * @param fieldName The field name to get type for
     * @return The jOOQ record class, or null if not a jOOQ record type
     */
    @SuppressWarnings("unchecked")
    public static Optional<Class<? extends UpdatableRecordImpl<?>>> getJooqRecordClassReturnedFromFieldGetter(Class<?> recordClass, String fieldName) {
        String getterName = "get" + capitalize(fieldName);
        try {
            Method getter = recordClass.getMethod(getterName);
            Class<?> returnType = getter.getReturnType();
            if (UpdatableRecordImpl.class.isAssignableFrom(returnType)) {
                return Optional.of((Class<? extends UpdatableRecordImpl<?>>) returnType);
            }
            return Optional.empty();
        } catch (NoSuchMethodException e) {
            return Optional.empty();
        }
    }
}
