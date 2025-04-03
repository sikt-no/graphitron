package no.fellesstudentsystem.schema_transformer.transform;

import graphql.Scalars;
import graphql.language.Directive;
import graphql.language.DirectiveDefinition;
import graphql.language.DirectivesContainer;
import graphql.language.FieldDefinition;
import graphql.language.ImplementingTypeDefinition;
import graphql.language.InputValueDefinition;
import graphql.language.IntValue;
import graphql.language.InterfaceTypeDefinition;
import graphql.language.ListType;
import graphql.language.NamedNode;
import graphql.language.NonNullType;
import graphql.language.ObjectTypeDefinition;
import graphql.language.SDLDefinition;
import graphql.language.StringValue;
import graphql.language.Type;
import graphql.language.TypeName;
import graphql.schema.idl.SchemaParser;
import graphql.schema.idl.TypeDefinitionRegistry;
import org.jetbrains.annotations.NotNull;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import static no.fellesstudentsystem.schema_transformer.mapping.GraphQLDirective.AS_CONNECTION;
import static no.fellesstudentsystem.schema_transformer.mapping.GraphQLDirectiveParam.CONNECTION_NAME;
import static no.fellesstudentsystem.schema_transformer.mapping.GraphQLDirectiveParam.FIRST_DEFAULT;
import static no.sikt.graphql.naming.GraphQLReservedName.CONNECTION_CURSOR_FIELD;
import static no.sikt.graphql.naming.GraphQLReservedName.CONNECTION_EDGE_FIELD;
import static no.sikt.graphql.naming.GraphQLReservedName.CONNECTION_NODES_FIELD;
import static no.sikt.graphql.naming.GraphQLReservedName.CONNECTION_NODE_FIELD;
import static no.sikt.graphql.naming.GraphQLReservedName.CONNECTION_PAGE_INFO_NODE;
import static no.sikt.graphql.naming.GraphQLReservedName.CONNECTION_TOTAL_COUNT;
import static no.sikt.graphql.naming.GraphQLReservedName.END_CURSOR_FIELD;
import static no.sikt.graphql.naming.GraphQLReservedName.HAS_NEXT_PAGE_FIELD;
import static no.sikt.graphql.naming.GraphQLReservedName.HAS_PREVIOUS_PAGE_FIELD;
import static no.sikt.graphql.naming.GraphQLReservedName.PAGINATION_AFTER;
import static no.sikt.graphql.naming.GraphQLReservedName.PAGINATION_FIRST;
import static no.sikt.graphql.naming.GraphQLReservedName.START_CURSOR_FIELD;


public class MakeConnections {
    private static final Map<String, DirectiveDefinition> directives = new SchemaParser()
            .parse(MakeConnections.class.getResourceAsStream("/schema/directives.graphqls"))
            .getDirectiveDefinitions();

    private static final TypeName
            STRING_TYPE = new TypeName(Scalars.GraphQLString.getName()),
            INT_TYPE = new TypeName(Scalars.GraphQLInt.getName()),
            BOOLEAN_TYPE = new TypeName(Scalars.GraphQLBoolean.getName()),
            PAGE_INFO = new TypeName(CONNECTION_PAGE_INFO_NODE.getName());

    public static void transform(TypeDefinitionRegistry typeDefinitionRegistry) {
        var objectTypeDefinitions = typeDefinitionRegistry.getTypes(ObjectTypeDefinition.class);
        for (var objectTypeDefinition : objectTypeDefinitions) {
            var fields = rewriteTypeDefinition(typeDefinitionRegistry, objectTypeDefinition);

            if (!fields.isEmpty()) {
                if (!typeDefinitionRegistry.hasType(PAGE_INFO)) {
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
                if (!typeDefinitionRegistry.hasType(PAGE_INFO)) {
                    typeDefinitionRegistry.add(createPageInfo());
                }

                typeDefinitionRegistry.remove(interfaceTypeDefinition);
                typeDefinitionRegistry.add(interfaceTypeDefinition.transform(builder -> builder.definitions(fields)));
            }
        }
        typeDefinitionRegistry
                .getDirectiveDefinition(AS_CONNECTION.getName())
                .ifPresent(typeDefinitionRegistry::remove);
    }

    private static <T extends DirectivesContainer<T> & ImplementingTypeDefinition<T>> List<FieldDefinition> rewriteTypeDefinition(TypeDefinitionRegistry typeDefinitionRegistry, T objectTypeDefinition) {
        var transformedFields = false;
        var fields = new ArrayList<FieldDefinition>();
        for (var fieldDefinition : objectTypeDefinition.getFieldDefinitions()) {
            if (fieldDefinition.hasDirective(AS_CONNECTION.getName())) {
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

    private static SDLDefinition<?> createPageInfo() {
        return ObjectTypeDefinition.newObjectTypeDefinition()
                .name(CONNECTION_PAGE_INFO_NODE.getName())
                .fieldDefinitions(List.of(
                        FieldDefinition.newFieldDefinition()
                                .name(HAS_PREVIOUS_PAGE_FIELD.getName())
                                .type(new NonNullType(BOOLEAN_TYPE))
                                .build(),
                        FieldDefinition.newFieldDefinition()
                                .name(HAS_NEXT_PAGE_FIELD.getName())
                                .type(new NonNullType(BOOLEAN_TYPE))
                                .build(),
                        FieldDefinition.newFieldDefinition()
                                .name(START_CURSOR_FIELD.getName())
                                .type(STRING_TYPE)
                                .build(),
                        FieldDefinition.newFieldDefinition()
                                .name(END_CURSOR_FIELD.getName())
                                .type(STRING_TYPE)
                                .build()))
                .build();
    }

    private static <T extends DirectivesContainer<T> & ImplementingTypeDefinition<T>> FieldDefinition transformListWrapperToConnection(TypeDefinitionRegistry typeDefinitionRegistry, T objectTypeDefinition, FieldDefinition fieldDefinition) {
        var wrappedType = getWrappedType(objectTypeDefinition, fieldDefinition);
        var connections = fieldDefinition.getDirectives(AS_CONNECTION.getName());
        if (connections.size() > 1) {
            throw new IllegalArgumentException(String.format("The field %s.%s has more than one connection directive. This is not supported.", objectTypeDefinition.getName(), fieldDefinition.getName()));
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
                            .name(PAGINATION_FIRST.getName())
                            .type(INT_TYPE)
                            .defaultValue(getDefaultFirstValue(connection))
                    .build());

            builder.inputValueDefinition(InputValueDefinition
                    .newInputValueDefinition()
                    .name(PAGINATION_AFTER.getName())
                    .type(STRING_TYPE)
                    .build());

            // Filtrer bort asConnection-direktivet
            builder.directives(
                    fieldDefinition.getDirectives()
                            .stream()
                            .filter(directive -> !AS_CONNECTION.getName().equals(directive.getName()))
                            .collect(Collectors.toList())
            );
        });
    }

    private static String getConnectionTypeName(NamedNode<?> objectTypeDefinition, FieldDefinition fieldDefinition, Directive connection) {
        var forcedConnectionNameArgument = connection.getArgument(AS_CONNECTION.getParamName(CONNECTION_NAME));
        if (forcedConnectionNameArgument != null && forcedConnectionNameArgument.getValue() != null) {
            var value = (StringValue) forcedConnectionNameArgument.getValue();
            return value.getValue();
        }

        return String.format("%s%sConnection", objectTypeDefinition.getName(), capitalize(fieldDefinition.getName()));
    }

    @NotNull
    private static Type<?> getWrappedType(NamedNode<?> objectTypeDefinition, FieldDefinition fieldDefinition) {
        var fieldType = fieldDefinition.getType();
        if (fieldType instanceof NonNullType) {
            fieldType = ((NonNullType) fieldType).getType();
        }

        if (!(fieldType instanceof ListType)) {
            throw new IllegalArgumentException(String.format("The field %s.%s is not a list type, this is not supported.", objectTypeDefinition.getName(), fieldDefinition.getName()));
        }

        var wrappedType = ((ListType) fieldType).getType();
        if (wrappedType instanceof NonNullType) {
            wrappedType = ((NonNullType) wrappedType).getType();
        }

        if (!(wrappedType instanceof TypeName)) {
            throw new IllegalArgumentException(String.format("The field %s.%s has no name, this is not supported.", objectTypeDefinition.getName(), fieldDefinition.getName()));
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
                                    .name(CONNECTION_CURSOR_FIELD.getName())
                                    .type(STRING_TYPE)
                                    .build(),
                            FieldDefinition.newFieldDefinition()
                                    .name(CONNECTION_NODE_FIELD.getName())
                                    .type(wrappedType)
                                    .build()
                    ))
                    .build();

            var connection = ObjectTypeDefinition.newObjectTypeDefinition()
                    .name(connectionTypeName)
                    .fieldDefinitions(List.of(
                            FieldDefinition.newFieldDefinition()
                                    .name(CONNECTION_EDGE_FIELD.getName())
                                    .type(new ListType(edgeType))
                                    .build(),
                            FieldDefinition.newFieldDefinition()
                                    .name("pageInfo")
                                    .type(PAGE_INFO)
                                    .build(),
                            FieldDefinition.newFieldDefinition()
                                    .name(CONNECTION_NODES_FIELD.getName())
                                    .type(fieldDefinition.getType())
                                    .build(),
                            FieldDefinition.newFieldDefinition()
                                    .name(CONNECTION_TOTAL_COUNT.getName())
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
        var defaultFirstValueArgument = connection.getArgument(AS_CONNECTION.getParamName(FIRST_DEFAULT));
        if (defaultFirstValueArgument != null) {
            return (IntValue) defaultFirstValueArgument.getValue();
        }

        return getDefaultFirstValueFromInputValueDefinitions();
    }

    private static IntValue getDefaultFirstValueFromInputValueDefinitions() {
        var inputValueDefinitions = directives.get(AS_CONNECTION.getName()).getInputValueDefinitions();
        return getDefaultFirstValueFromInputValueDefinitions(inputValueDefinitions)
                .orElse(new IntValue(BigInteger.valueOf(1000)));
    }

    private static Optional<IntValue> getDefaultFirstValueFromInputValueDefinitions(List<InputValueDefinition> inputValueDefinitions) {
        return inputValueDefinitions
                .stream()
                .filter(definition -> definition.getName().equals(AS_CONNECTION.getParamName(FIRST_DEFAULT)))
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
