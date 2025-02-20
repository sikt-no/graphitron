package no.fellesstudentsystem.schema_transformer.schema.rewrites;

import graphql.language.*;
import graphql.schema.idl.SchemaParser;
import graphql.schema.idl.TypeDefinitionRegistry;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;


public class AsConnectionRewriter {
    private static final Logger logger = LoggerFactory.getLogger(AsConnectionRewriter.class);
    private static final Map<String, DirectiveDefinition> directives = new SchemaParser()
            .parse(AsConnectionRewriter.class.getResourceAsStream("/schema/directives.graphqls"))
            .getDirectiveDefinitions();
    private static final String DIRECTIVE_NAME = "asConnection";

    private static final String DEFAULT_FIRST_VALUE_ARGUMENT_NAME = "defaultFirstValue";
    private static final String FORCE_CONNECTION_NAME_ARGUMENT_NAME = "connectionName";
    private static final TypeName BOOLEAN_TYPE = new TypeName("Boolean");
    private static final TypeName STRING_TYPE = new TypeName("String");
    public static final TypeName INT_TYPE = new TypeName("Int");

    public static void rewrite(TypeDefinitionRegistry typeDefinitionRegistry) {
        var objectTypeDefinitions = typeDefinitionRegistry.getTypes(ObjectTypeDefinition.class);
        for (var objectTypeDefinition : objectTypeDefinitions) {
            var fields = rewriteTypeDefinition(typeDefinitionRegistry, objectTypeDefinition);

            if (!fields.isEmpty()) {
                if (!typeDefinitionRegistry.hasType(new TypeName("PageInfo"))) {
                    typeDefinitionRegistry.add(createPageInfo());
                }

                typeDefinitionRegistry.remove(objectTypeDefinition);
                typeDefinitionRegistry.add(objectTypeDefinition.transform(builder -> builder.fieldDefinitions(fields)));
            }
        }

        var interfaceTypeDefinitions = typeDefinitionRegistry.getTypes(InterfaceTypeDefinition.class);
        for (var interfaceTypeDefinition : interfaceTypeDefinitions) {
            var fields = rewriteTypeDefinition(typeDefinitionRegistry, interfaceTypeDefinition);

            if (!fields.isEmpty()) {
                if (!typeDefinitionRegistry.hasType(new TypeName("PageInfo"))) {
                    typeDefinitionRegistry.add(createPageInfo());
                }

                typeDefinitionRegistry.remove(interfaceTypeDefinition);
                typeDefinitionRegistry.add(interfaceTypeDefinition.transform(builder -> builder.definitions(fields)));
            }
        }
    }

    private static <T extends DirectivesContainer<T> & ImplementingTypeDefinition<T>> List<FieldDefinition> rewriteTypeDefinition(TypeDefinitionRegistry typeDefinitionRegistry, T objectTypeDefinition) {
        var transformedFields = false;
        var fields = new ArrayList<FieldDefinition>();
        for (var fieldDefinition : objectTypeDefinition.getFieldDefinitions()) {
            if (fieldDefinition.hasDirective(DIRECTIVE_NAME)) {
                transformedFields = true;

                var newFieldDefinition = transformListWrapperToConnection(typeDefinitionRegistry, objectTypeDefinition, fieldDefinition);
                fields.add(newFieldDefinition);
            } else {
                fields.add(fieldDefinition);
            }
        }

        if (transformedFields) {
            return fields;
        } else {
            return List.of();
        }
    }

    private static SDLDefinition createPageInfo() {
        return ObjectTypeDefinition.newObjectTypeDefinition()
                .name("PageInfo")
                .fieldDefinitions(List.of(
                        FieldDefinition.newFieldDefinition()
                                .name("hasPreviousPage")
                                .type(new NonNullType(BOOLEAN_TYPE))
                                .build(),
                        FieldDefinition.newFieldDefinition()
                                .name("hasNextPage")
                                .type(new NonNullType(BOOLEAN_TYPE))
                                .build(),
                        FieldDefinition.newFieldDefinition()
                                .name("startCursor")
                                .type(STRING_TYPE)
                                .build(),
                        FieldDefinition.newFieldDefinition()
                                .name("endCursor")
                                .type(STRING_TYPE)
                                .build()))
                .build();
    }

    private static <T extends DirectivesContainer<T> & ImplementingTypeDefinition<T>> FieldDefinition transformListWrapperToConnection(TypeDefinitionRegistry typeDefinitionRegistry, T objectTypeDefinition, FieldDefinition fieldDefinition) {
        var wrappedType = getWrappedType(objectTypeDefinition, fieldDefinition);
        var connections = fieldDefinition.getDirectives(DIRECTIVE_NAME);
        if (connections.size() > 1) {
            throw new IllegalArgumentException(String.format("Feltet %s.%s har mer enn ett connection-direktiv. Dette er ikke støttet.", objectTypeDefinition.getName(), fieldDefinition.getName()));
        }
        var connection = connections.get(0);

        var connectionTypeName = getConnectionTypeName(objectTypeDefinition, fieldDefinition, connection);
        var connectionType = maybeCreateConnectionType(connectionTypeName, typeDefinitionRegistry, fieldDefinition, (TypeName) wrappedType);
        return fieldDefinition.transform(builder -> {
            // 2. Endre felttypen til å peke på Connection-typen
            builder.type(connectionType);

            // 3. Endre felttypen til å ha first og after-argument
            builder.inputValueDefinition(InputValueDefinition
                    .newInputValueDefinition()
                            .name("first")
                            .type(INT_TYPE)
                            .defaultValue(getDefaultFirstValue(connection))
                    .build());

            builder.inputValueDefinition(InputValueDefinition
                    .newInputValueDefinition()
                    .name("after")
                    .type(STRING_TYPE)
                    .build());

            // Filtrer bort asConnection-direktivet
            builder.directives(
                    fieldDefinition.getDirectives()
                            .stream()
                            .filter(directive -> !DIRECTIVE_NAME.equals(directive.getName()))
                            .collect(Collectors.toList())
            );
        });
    }

    private static String getConnectionTypeName(NamedNode<?> objectTypeDefinition, FieldDefinition fieldDefinition, Directive connection) {
        var forcedConnectionNameArgument = connection.getArgument(FORCE_CONNECTION_NAME_ARGUMENT_NAME);
        if (forcedConnectionNameArgument != null && forcedConnectionNameArgument.getValue() != null) {
            var value = (StringValue) forcedConnectionNameArgument.getValue();
            return value.getValue();
        }

        return String.format("%s%sConnection", objectTypeDefinition.getName(), capitalize(fieldDefinition.getName()));
    }

    @NotNull
    private static Type getWrappedType(NamedNode<?> objectTypeDefinition, FieldDefinition fieldDefinition) {
        var fieldType = fieldDefinition.getType();
        if (fieldType instanceof NonNullType) {
            fieldType = ((NonNullType) fieldType).getType();
        }

        if (!(fieldType instanceof ListType)) {
            throw new IllegalArgumentException(String.format("Feltet %s.%s er ikke en listetype, dette er ikke støttet.", objectTypeDefinition.getName(), fieldDefinition.getName()));
        }

        var wrappedType = ((ListType) fieldType).getType();
        if (wrappedType instanceof NonNullType) {
            wrappedType = ((NonNullType) wrappedType).getType();
        }

        if (!(wrappedType instanceof TypeName)) {
            throw new IllegalArgumentException(String.format("Feltet %s.%s har ikke navn, dette er ikke støttet.", objectTypeDefinition.getName(), fieldDefinition.getName()));
        }
        return wrappedType;
    }

    @NotNull
    private static TypeName maybeCreateConnectionType(String connectionTypeName, TypeDefinitionRegistry typeDefinitionRegistry, FieldDefinition fieldDefinition, TypeName wrappedType) {
        // 1. Opprett og legg til Connection- og Edge-typene i typeDefinitionRegistry
        //    dersom Connection-typen ikke allerede er definert.
        //    TODO: kopiere feature-direktiv fra parent-feltet?

        var connectionType = new TypeName(connectionTypeName);
        if (!typeDefinitionRegistry.hasType(connectionType)) {
            var edgeType = new TypeName(connectionTypeName + "Edge");
            var edge = ObjectTypeDefinition.newObjectTypeDefinition()
                    .name(edgeType.getName())
                    .fieldDefinitions(List.of(
                            FieldDefinition.newFieldDefinition()
                                    .name("cursor")
                                    .type(STRING_TYPE)
                                    .build(),
                            FieldDefinition.newFieldDefinition()
                                    .name("node")
                                    .type(wrappedType)
                                    .build()
                    ))
                    .build();

            var connection = ObjectTypeDefinition.newObjectTypeDefinition()
                    .name(connectionTypeName)
                    .fieldDefinitions(List.of(
                            FieldDefinition.newFieldDefinition()
                                    .name("edges")
                                    .type(new ListType(edgeType))
                                    .build(),
                            FieldDefinition.newFieldDefinition()
                                    .name("pageInfo")
                                    .type(new TypeName("PageInfo"))
                                    .build(),
                            FieldDefinition.newFieldDefinition()
                                    .name("nodes")
                                    .type(fieldDefinition.getType())
                                    .build(),
                            FieldDefinition.newFieldDefinition()
                                    .name("totalCount")
                                    .type(INT_TYPE)
                                    .build()
                    ))
                    .build();
            typeDefinitionRegistry.add(connection);
            typeDefinitionRegistry.add(edge);
        }
        return connectionType;
    }

    private static IntValue getDefaultFirstValue(Directive connection) {
        var defaultFirstValueArgument = connection.getArgument(DEFAULT_FIRST_VALUE_ARGUMENT_NAME);
        if (defaultFirstValueArgument != null) {
            return (IntValue) defaultFirstValueArgument.getValue();
        }

        return getDefaultFirstValueFromInputValueDefinitions();
    }

    private static IntValue getDefaultFirstValueFromInputValueDefinitions() {
        var inputValueDefinitions = directives.get(DIRECTIVE_NAME).getInputValueDefinitions();
        return getDefaultFirstValueFromInputValueDefinitions(inputValueDefinitions)
                .orElse(new IntValue(BigInteger.valueOf(1000)));
    }

    private static Optional<IntValue> getDefaultFirstValueFromInputValueDefinitions(List<InputValueDefinition> inputValueDefinitions) {
        return inputValueDefinitions
                .stream()
                .filter(definition -> definition.getName().equals(DEFAULT_FIRST_VALUE_ARGUMENT_NAME))
                .map(x -> (IntValue) x.getDefaultValue())
                .findFirst();
    }

    public static CharSequence capitalize(CharSequence input) {
        int length;
        if (input == null || (length = input.length()) == 0) return input;

        return new StringBuilder(length)
            .appendCodePoint(Character.toTitleCase(Character.codePointAt(input, 0)))
            .append(input, Character.offsetByCodePoints(input, 0, 1), length);
    }
}
