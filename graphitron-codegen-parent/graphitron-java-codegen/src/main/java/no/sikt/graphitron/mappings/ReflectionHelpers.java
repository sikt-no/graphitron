package no.sikt.graphitron.mappings;

import no.sikt.graphitron.definitions.interfaces.GenerationField;
import no.sikt.graphql.schema.ProcessedSchema;
import org.jooq.impl.UpdatableRecordImpl;

import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.util.Arrays;
import java.util.List;
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
            if (List.class.isAssignableFrom(returnType) && getter.getGenericReturnType() instanceof ParameterizedType parameterized) {
                var typeArg = parameterized.getActualTypeArguments()[0];
                if (typeArg instanceof Class<?> elementClass && UpdatableRecordImpl.class.isAssignableFrom(elementClass)) {
                    return Optional.of((Class<? extends UpdatableRecordImpl<?>>) elementClass);
                }
            }
            return Optional.empty();
        } catch (NoSuchMethodException e) {
            return Optional.empty();
        }
    }

    /**
     * Gets the jOOQ record class that a @nodeId field should produce.
     * Uses reflection on the Java record class to get the target field's type.
     * @param field The @nodeId field
     * @return The jOOQ record class, or null if the field is not targeting a jOOQ record field
     */
    public static Optional<Class<? extends UpdatableRecordImpl<?>>> getJooqRecordClassForNodeIdInputField(GenerationField field, ProcessedSchema schema) {
        if (!schema.isNodeIdField(field)) return Optional.empty();
        var containerType = schema.getInputType(field.getContainerTypeName());
        if (containerType == null || !containerType.hasJavaRecordReference()) return Optional.empty();

        Class<?> javaRecordClass = containerType.getRecordReference();
        String targetFieldName = field.getJavaRecordMethodMapping(true).getName();
        return ReflectionHelpers.getJooqRecordClassReturnedFromFieldGetter(javaRecordClass, targetFieldName);
    }
}
