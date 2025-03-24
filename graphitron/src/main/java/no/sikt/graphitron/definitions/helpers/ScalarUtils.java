package no.sikt.graphitron.definitions.helpers;

import com.palantir.javapoet.ClassName;
import graphql.Scalars;
import graphql.scalars.ExtendedScalars;
import graphql.schema.GraphQLScalarType;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class ScalarUtils {

    private static final Set<String> BUILT_IN_SCALAR_NAMES;
    private static final Map<String, String> EXTENDED_SCALARS;

    // The two maps below contains all scalars from the ExtendedScalars class.
    private static final Map<String, String> EXTENDED_SCALARS_TYPE_NAME_MAPPING;
    private static final Map<String, ClassName> EXTENDED_SCALARS_TYPE_MAPPING;

    private static Map<String, String> userProvidedScalarsTypeNameMapping;
    private static Map<String, ClassName> userProvidedScalarsTypeMapping;

    static {
        BUILT_IN_SCALAR_NAMES = getScalarFields(Scalars.class).values()
                .stream()
                .map(GraphQLScalarType::getName)
                .collect(Collectors.toSet());

        var extendedScalars = getScalarFields(ExtendedScalars.class);
        EXTENDED_SCALARS = extendedScalars.entrySet()
                .stream()
                .collect(Collectors.toMap(entry -> entry.getValue().getName(), Map.Entry::getKey));

        EXTENDED_SCALARS_TYPE_MAPPING = new HashMap<>();
        extendedScalars.forEach((fieldName, scalarType) -> {
            try {
                Class<?> inputClass = scalarType.getCoercing().getClass().getMethod("parseValue", Object.class).getReturnType();
                EXTENDED_SCALARS_TYPE_MAPPING.put(scalarType.getName(), ClassName.get(inputClass));
            } catch (NoSuchMethodException e) {
                throw new RuntimeException("Failed to access scalar field: " + fieldName, e);
            }
        });

        EXTENDED_SCALARS_TYPE_NAME_MAPPING = EXTENDED_SCALARS_TYPE_MAPPING.entrySet().stream()
                .map(it -> Map.entry(it.getKey(), it.getValue().canonicalName()))
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));

        userProvidedScalarsTypeMapping = new HashMap<>();
        userProvidedScalarsTypeNameMapping = new HashMap<>();
    }

    /**
     * @return the names of the built-in scalar types supported by GraphQL Java.
     */
    public static Set<String> getBuiltInScalarNames() {
        return BUILT_IN_SCALAR_NAMES;
    }

    /**
     * @return a map of extended scalar names to their corresponding field names from the {@link ExtendedScalars} class.
     * The map only contains the extended scalars that are not overridden by user provided scalars.
     * This is because the user provided scalars need to be added manually to the wiring.
     */
    public static Map<String, String> getAllExtendedScalarsNotOverriddenByUserProvidedScalars() {
        return EXTENDED_SCALARS.entrySet().stream()
                .filter(entry -> !userProvidedScalarsTypeNameMapping.containsKey(entry.getKey()))
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    /**
     * @return a map of all supported scalar names to their corresponding Java class names.
     * I.e. the combined mapping of extended scalars and the user provided scalars.
     * The built-in scalars are not included in this mapping because they are already known by the GraphQL Java library.
     */
    public static Map<String, String> getCustomScalarsTypeNameMapping() {
        Map<String, String> combinedMapping = new HashMap<>(EXTENDED_SCALARS_TYPE_NAME_MAPPING);
        combinedMapping.putAll(userProvidedScalarsTypeNameMapping);
        return combinedMapping;
    }

    /**
     * @return the java ClassName type mapping for a supported scalar type.
     * User provided scalars take precedence over extended scalars.
     * If the scalar type is not recognized, an IllegalArgumentException is thrown.
     */
    public static ClassName getCustomScalarTypeMapping(String scalarName) {
        return Optional.ofNullable(userProvidedScalarsTypeMapping.get(scalarName))
                .orElseGet(() -> Optional.ofNullable(EXTENDED_SCALARS_TYPE_MAPPING.get(scalarName))
                        .orElseThrow(() -> new IllegalArgumentException(
                                "Scalar type mapping not found for '" + scalarName + "'. Configured scalars: " +
                                        Stream.of(userProvidedScalarsTypeMapping.keySet(), EXTENDED_SCALARS_TYPE_MAPPING.keySet())
                                                .flatMap(Set::stream)
                                                .sorted()
                                                .collect(Collectors.joining(", ")))));
    }

    public static void setUserProvidedScalars(Map<String, Class<?>> typeMapping) {
        userProvidedScalarsTypeMapping = typeMapping.entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, entry -> ClassName.get(entry.getValue())));
        userProvidedScalarsTypeNameMapping = typeMapping.entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, entry -> entry.getValue().getCanonicalName()));
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