package no.sikt.graphitron.mappings;

import org.jooq.impl.UpdatableRecordImpl;

import java.lang.reflect.Method;
import java.util.Arrays;

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
     * Checks if a field in a Java record class has a type that extends UpdatableRecordImpl (jOOQ record).
     * @param recordClass The Java record class to inspect
     * @param fieldName The field name to check (will look for getter method)
     * @return true if the field type is a jOOQ record type
     */
    public static boolean isFieldTypeJooqRecord(Class<?> recordClass, String fieldName) {
        String getterName = "get" + capitalize(fieldName);
        try {
            Method getter = recordClass.getMethod(getterName);
            Class<?> returnType = getter.getReturnType();
            return UpdatableRecordImpl.class.isAssignableFrom(returnType);
        } catch (NoSuchMethodException e) {
            return false;
        }
    }

    /**
     * Gets the jOOQ record class for a field in a Java record class.
     * @param recordClass The Java record class to inspect
     * @param fieldName The field name to get type for
     * @return The jOOQ record class, or null if not a jOOQ record type
     */
    @SuppressWarnings("unchecked")
    public static Class<? extends UpdatableRecordImpl<?>> getJooqRecordFieldType(Class<?> recordClass, String fieldName) {
        String getterName = "get" + capitalize(fieldName);
        try {
            Method getter = recordClass.getMethod(getterName);
            Class<?> returnType = getter.getReturnType();
            if (UpdatableRecordImpl.class.isAssignableFrom(returnType)) {
                return (Class<? extends UpdatableRecordImpl<?>>) returnType;
            }
            return null;
        } catch (NoSuchMethodException e) {
            return null;
        }
    }
}
