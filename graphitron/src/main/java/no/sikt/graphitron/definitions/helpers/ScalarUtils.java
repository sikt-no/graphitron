package no.sikt.graphitron.definitions.helpers;

import graphql.Scalars;
import graphql.scalars.ExtendedScalars;
import graphql.schema.GraphQLScalarType;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class ScalarUtils {

    /**
     * Retrieves the names of the built-in scalar types supported by the GraphQL Java library.
     *
     * @return a set of names of the built-in scalar types
     */
    public static Set<String> getBuiltInScalarNames() {
        return getScalarFields(Scalars.class).values()
                .stream()
                .map(GraphQLScalarType::getName)
                .collect(Collectors.toSet());
    }

    /**
     * @return a map of extended scalar names to their corresponding field names from the {@link ExtendedScalars} class.
     */
    public static Map<String, String> getExtendedScalars() {
        return getScalarFields(ExtendedScalars.class).entrySet()
                .stream()
                .collect(Collectors.toMap(entry -> entry.getValue().getName(), Map.Entry::getKey));
    }

    /**
     * @return a map of extended scalar names to their corresponding java class names
     */
    public static Map<String, String> getExtendedScalarsTypeMapping() {
        var customTypesMapping = new HashMap<String, String>();
        var extendedScalars = getScalarFields(ExtendedScalars.class);
        extendedScalars.forEach((fieldName, scalarType) -> {
            try {
                Class<?> inputClass = scalarType.getCoercing().getClass().getMethod("parseValue", Object.class).getReturnType();
                customTypesMapping.put(scalarType.getName(), inputClass.getName());
            } catch (NoSuchMethodException e) {
                throw new RuntimeException("Failed to access scalar field: " + fieldName, e);
            }
        });
        return customTypesMapping;
    }

    /**
     * This method uses reflection to access all public fields of the provided class
     * that are of type {@link GraphQLScalarType}. It then creates a map where the keys are the names of these fields
     * and the values are the corresponding scalar types.
     */
    private static Map<String, GraphQLScalarType> getScalarFields(Class<?> clazz) {
        return Stream.of(clazz.getFields())
                .filter(field -> GraphQLScalarType.class.isAssignableFrom(field.getType()))
                .collect(Collectors.toMap(
                        Field::getName,
                        field -> {
                            try {
                                return (GraphQLScalarType) field.get(null);
                            } catch (IllegalAccessException e) {
                                throw new RuntimeException("Failed to access scalar field: " + field.getName(), e);
                            }
                        }
                ));
    }
}