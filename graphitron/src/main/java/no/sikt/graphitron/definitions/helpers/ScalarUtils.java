package no.sikt.graphitron.definitions.helpers;

import com.apollographql.federation.graphqljava._Any;
import com.apollographql.federation.graphqljava._FieldSet;
import com.apollographql.federation.graphqljava.link__Import;
import no.sikt.graphitron.javapoet.ClassName;
import no.sikt.graphitron.javapoet.CodeBlock;
import graphql.Scalars;
import graphql.scalars.ExtendedScalars;
import graphql.schema.GraphQLScalarType;
import no.sikt.graphql.schema.CustomScalars;

import java.lang.reflect.Field;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class ScalarUtils {

    private static ScalarUtils instance = null;

    private final Set<String> BUILT_IN_SCALAR_NAMES;

    //order matters. Any custom scalars that should override the default ones should be added last.
    private static final List<Class<?>> DEFAULT_SCALAR_DEFINITIONS_CLASSES =
            List.of(Scalars.class, ExtendedScalars.class, _FieldSet.class, _Any.class, link__Import.class, CustomScalars.class);

    private final Map<String, CodeBlock> scalarTypeCodeBlockMapping;
    private final Map<String, String> scalarTypeNameMapping;
    private final Map<String, ClassName> scalarsTypeClassMapping;

    private ScalarUtils(Set<Class<?>> userProvidedDefinitionsClasses) {
        BUILT_IN_SCALAR_NAMES = getScalarFields(Scalars.class).values()
                .stream()
                .map(GraphQLScalarType::getName)
                .collect(Collectors.toSet());

        scalarTypeCodeBlockMapping = new HashMap<>();
        scalarTypeNameMapping = new HashMap<>();
        scalarsTypeClassMapping = new HashMap<>();

        var scalarDefinitionsClasses =
                Stream.concat(
                        DEFAULT_SCALAR_DEFINITIONS_CLASSES.stream(),
                        userProvidedDefinitionsClasses.stream()
                );

        scalarDefinitionsClasses
                .forEach(scalarDefinitionsClass -> {
                    var scalars = getScalarFields(scalarDefinitionsClass);
                    var scalarNameToClassNames = getScalarNameToClassNameMap(scalars);
                    scalarsTypeClassMapping.putAll(scalarNameToClassNames);
                    scalarTypeNameMapping.putAll(getScalarNameToJavaClassNameMap(scalarNameToClassNames));
                    scalarTypeCodeBlockMapping.putAll(getScalarTypeNameToFieldNameMap(scalars, scalarDefinitionsClass));
                });
    }

    public static ScalarUtils getInstance() {
        if (instance == null) {
            return new ScalarUtils(Set.of());
        }
        return instance;
    }

    public static ScalarUtils initialize(Set<Class<?>> definitionsClasses) {
        instance = new ScalarUtils(definitionsClasses);
        return instance;
    }

    /**
     * @return the names of the built-in scalar types supported by GraphQL Java.
     */
    public Set<String> getBuiltInScalarNames() {
        return BUILT_IN_SCALAR_NAMES;
    }

    /**
     * @return a map of scalar names to CodeBlocks representing the code to access the scalar field.
     * E.g "BigDecimal" -> "ExtendedScalars.GraphQLBigDecimal".
     * This is used to automatically add scalar types to the wiring.
     */
    public Map<String, CodeBlock> getScalarTypeCodeBlockMapping() {
        return scalarTypeCodeBlockMapping;
    }

    /**
     * @return a map of all supported scalar names to their corresponding Java class names.
     */
    public Map<String, String> getScalarTypeNameMapping() {
        return scalarTypeNameMapping;
    }

    /**
     * @return the java ClassName type mapping for a supported scalar type.
     * Null if the scalar is not supported.
     */
    public ClassName getScalarTypeMapping(String scalarName) {
        return scalarsTypeClassMapping.get(scalarName);
    }

    private Map<String, CodeBlock> getScalarTypeNameToFieldNameMap(Map<String, GraphQLScalarType> scalars, Class<?> containingClass) {
        return scalars.entrySet()
                .stream()
                .collect(Collectors.toMap(entry -> entry.getValue().getName(),
                        entry -> CodeBlock.of("$T.$N", containingClass, entry.getKey())));
    }

    private Map<String, ClassName> getScalarNameToClassNameMap(Map<String, GraphQLScalarType> scalars) {
        HashMap<String, ClassName> extendedScalarsTypeMapping = new HashMap<>();
        scalars.forEach((fieldName, scalarType) -> {
            try {
                Type[] genericInterfaces = scalarType.getCoercing().getClass().getGenericInterfaces();
                for (Type genericInterface : genericInterfaces) {
                    if (genericInterface instanceof ParameterizedType parameterizedType) {
                        Type[] actualTypeArguments = parameterizedType.getActualTypeArguments();
                        if (actualTypeArguments.length > 0) {
                            Type inputType = actualTypeArguments[0];
                            if (inputType instanceof Class<?> inputClass) {
                                extendedScalarsTypeMapping.put(scalarType.getName(), ClassName.get(inputClass));
                                return;
                            }
                        }
                    }
                }
                // Fallback mechanism for scalars that do not have generic type parameters
                Class<?> inputClass = scalarType.getCoercing().getClass().getMethod("parseValue", Object.class).getReturnType();
                extendedScalarsTypeMapping.put(scalarType.getName(), ClassName.get(inputClass));
            } catch (NoSuchMethodException e) {
                throw new RuntimeException("Failed to access scalar field: " + fieldName, e);
            }
        });
        return extendedScalarsTypeMapping;
    }

    private Map<String, String> getScalarNameToJavaClassNameMap(Map<String, ClassName> scalarToClassNames) {
        return scalarToClassNames.entrySet().stream()
                .map(it -> Map.entry(it.getKey(), it.getValue().canonicalName()))
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    /**
     * This method uses reflection to access all public fields of the provided class
     * that are of type {@link GraphQLScalarType}. It then creates a map where the keys are the names of these fields
     * and the values are the corresponding scalar types.
     */
    private Map<String, GraphQLScalarType> getScalarFields(Class<?> clazz) {
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