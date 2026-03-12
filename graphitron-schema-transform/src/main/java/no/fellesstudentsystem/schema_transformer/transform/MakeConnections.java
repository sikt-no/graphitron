package no.fellesstudentsystem.schema_transformer.transform;

import graphql.Scalars;
import graphql.language.*;
import graphql.schema.idl.SchemaParser;
import graphql.schema.idl.TypeDefinitionRegistry;
import org.jetbrains.annotations.NotNull;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static no.fellesstudentsystem.schema_transformer.directives.TransformDirective.AS_CONNECTION;
import static no.fellesstudentsystem.schema_transformer.directives.TransformDirective.CONNECTION;
import static no.fellesstudentsystem.schema_transformer.directives.TransformDirectiveParam.CONNECTION_NAME;
import static no.fellesstudentsystem.schema_transformer.directives.TransformDirectiveParam.FIRST_DEFAULT;
import static no.sikt.graphql.naming.GraphQLReservedName.*;


public class MakeConnections {
    private static final Map<String, DirectiveDefinition> directives = new SchemaParser()
            .parse(MakeConnections.class.getResourceAsStream("/schema/directives.graphqls"))
            .getDirectiveDefinitions();
    private static final Directive SHAREABLE = Directive.newDirective().name(FEDERATION_SHAREABLE.getName()).build();

    private static final TypeName
            STRING_TYPE = new TypeName(Scalars.GraphQLString.getName()),
            INT_TYPE = new TypeName(Scalars.GraphQLInt.getName()),
            BOOLEAN_TYPE = new TypeName(Scalars.GraphQLBoolean.getName()),
            PAGE_INFO = new TypeName(CONNECTION_PAGE_INFO_NODE.getName());

    public static void transform(TypeDefinitionRegistry typeDefinitionRegistry) {
        transform(typeDefinitionRegistry, true, true);
    }

    public static void transform(
            TypeDefinitionRegistry typeDefinitionRegistry,
            boolean nodesFieldInConnectionsEnabled,
            boolean totalCountFieldInConnectionsEnabled
    ) {
        var queryLocation = Optional.ofNullable(typeDefinitionRegistry.getTypeOrNull(SCHEMA_QUERY.getName())).map(Node::getSourceLocation).orElse(null);

        var objectTypeDefinitions = typeDefinitionRegistry.getTypes(ObjectTypeDefinition.class);
        var interfaceTypeDefinitions = typeDefinitionRegistry.getTypes(InterfaceTypeDefinition.class);

        // If any field mapping to a given connection type name is shareable, the connection type itself must be shareable.
        var sharableConnectionTypes = collectSharableConnectionTypes(objectTypeDefinitions, interfaceTypeDefinitions);

        boolean addPageInfo = false;
        for (var objectTypeDefinition : objectTypeDefinitions) {
            var fields = rewriteTypeDefinition(
                    typeDefinitionRegistry,
                    objectTypeDefinition,
                    nodesFieldInConnectionsEnabled,
                    totalCountFieldInConnectionsEnabled,
                    sharableConnectionTypes
            );

            if (!fields.isEmpty()) {
                addPageInfo = true;
                typeDefinitionRegistry.remove(objectTypeDefinition);
                typeDefinitionRegistry.add(objectTypeDefinition.transform(builder -> builder.fieldDefinitions(fields)));
            }
        }

        for (var interfaceTypeDefinition : interfaceTypeDefinitions) {
            var fields = rewriteTypeDefinition(
                    typeDefinitionRegistry,
                    interfaceTypeDefinition,
                    nodesFieldInConnectionsEnabled,
                    totalCountFieldInConnectionsEnabled,
                    sharableConnectionTypes
            );

            if (!fields.isEmpty()) {
                addPageInfo = true;
                typeDefinitionRegistry.remove(interfaceTypeDefinition);
                typeDefinitionRegistry.add(interfaceTypeDefinition.transform(builder -> builder.definitions(fields)));
            }
        }

        if (addPageInfo && !typeDefinitionRegistry.hasType(PAGE_INFO)) {
            var pageInfo = createPageInfo(queryLocation, !sharableConnectionTypes.isEmpty() ? List.of(SHAREABLE) : List.of());
            typeDefinitionRegistry.add(pageInfo);
        }
        typeDefinitionRegistry
                .getDirectiveDefinition(AS_CONNECTION.getName())
                .ifPresent(typeDefinitionRegistry::remove);
    }

    private static Set<String> collectSharableConnectionTypes(List<ObjectTypeDefinition> objects, List<InterfaceTypeDefinition> interfaces) {
        return Stream.concat(
                objects.stream().flatMap(it -> collectSharableFields(it.getName(), it.getFieldDefinitions()).stream()),
                interfaces.stream().flatMap(it -> collectSharableFields(it.getName(), it.getFieldDefinitions()).stream())
        ).collect(Collectors.toSet());
    }

    private static List<String> collectSharableFields(String typeName, List<FieldDefinition> fields) {
        return fields
                .stream()
                .filter(it -> it.hasDirective(AS_CONNECTION.getName()) && it.hasDirective(FEDERATION_SHAREABLE.getName()))
                .map(it -> getConnectionTypeName(typeName, it.getName(), it.getDirectives(AS_CONNECTION.getName()).get(0)))
                .toList();
    }

    private static <T extends DirectivesContainer<T> & ImplementingTypeDefinition<T>> List<FieldDefinition> rewriteTypeDefinition(
            TypeDefinitionRegistry typeDefinitionRegistry,
            T objectTypeDefinition,
            boolean nodesFieldInConnectionsEnabled,
            boolean totalCountFieldInConnectionsEnabled,
            Set<String> sharableConnectionTypes
    ) {
        var definitions = objectTypeDefinition.getFieldDefinitions();
        if (definitions.stream().noneMatch(it -> it.hasDirective(AS_CONNECTION.getName()))) {
            return List.of();
        }

        return definitions.stream().map(it ->
                transformListWrapperToConnection(
                        typeDefinitionRegistry,
                        objectTypeDefinition,
                        it,
                        nodesFieldInConnectionsEnabled,
                        totalCountFieldInConnectionsEnabled,
                        sharableConnectionTypes
                )
        ).toList();
    }

    private static SDLDefinition<?> createPageInfo(SourceLocation sourceLocation, List<Directive> directives) {
        return ObjectTypeDefinition.newObjectTypeDefinition()
                .name(CONNECTION_PAGE_INFO_NODE.getName())
                .sourceLocation(sourceLocation)
                .directives(directives)
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

    private static <T extends DirectivesContainer<T> & ImplementingTypeDefinition<T>> FieldDefinition transformListWrapperToConnection(
            TypeDefinitionRegistry typeDefinitionRegistry,
            T objectTypeDefinition,
            FieldDefinition fieldDefinition,
            boolean nodesFieldInConnectionsEnabled,
            boolean totalCountFieldInConnectionsEnabled,
            Set<String> sharableConnectionTypes
    ) {
        if (!fieldDefinition.hasDirective(AS_CONNECTION.getName())) {
            return fieldDefinition;
        }

        var wrappedType = getWrappedType(objectTypeDefinition, fieldDefinition);
        var connections = fieldDefinition.getDirectives(AS_CONNECTION.getName());

        // Will never happen, validation in GraphQL will fail first as directive is not repeatable.
        if (connections.size() > 1) {
            throw new IllegalArgumentException(String.format("The field %s.%s has more than one %s directive. This is not supported.", objectTypeDefinition.getName(), fieldDefinition.getName(), CONNECTION.getName()));
        }
        var connection = connections.get(0);
        var connectionTypeName = getConnectionTypeName(objectTypeDefinition.getName(), fieldDefinition.getName(), connection);
        var directives = sharableConnectionTypes.contains(connectionTypeName) ? List.of(SHAREABLE) : List.<Directive>of();
        var connectionType = maybeCreateConnectionType(
                connectionTypeName,
                typeDefinitionRegistry,
                fieldDefinition,
                (TypeName) wrappedType,
                nodesFieldInConnectionsEnabled,
                totalCountFieldInConnectionsEnabled,
                directives
        );

        var source = fieldDefinition.getSourceLocation();
        return fieldDefinition.transform(builder -> {
            // 2. Endre felttypen til å peke på Connection-typen
            builder.type(connectionType);

            // 3. Endre felttypen til å ha first og after-argument
            builder.inputValueDefinition(
                    InputValueDefinition
                            .newInputValueDefinition()
                            .name(PAGINATION_FIRST.getName())
                            .type(INT_TYPE)
                            .defaultValue(getDefaultFirstValue(connection))
                            .sourceLocation(source)
                            .build()
            );

            builder.inputValueDefinition(
                    InputValueDefinition
                            .newInputValueDefinition()
                            .name(PAGINATION_AFTER.getName())
                            .type(STRING_TYPE)
                            .sourceLocation(source)
                            .build()
            );

            // Filtrer bort asConnection-direktivet
            builder.directives(
                    fieldDefinition.getDirectives()
                            .stream()
                            .filter(directive -> !AS_CONNECTION.getName().equals(directive.getName()))
                            .toList()
            );
        });
    }

    private static String getConnectionTypeName(String objectName, String fieldName, Directive connection) {
        var forcedConnectionNameArgument = connection.getArgument(AS_CONNECTION.getParamName(CONNECTION_NAME));
        if (forcedConnectionNameArgument == null || forcedConnectionNameArgument.getValue() == null) {
            return objectName + capitalize(fieldName) + SCHEMA_CONNECTION_SUFFIX.getName();
        }

        var value = (StringValue) forcedConnectionNameArgument.getValue();
        return value.getValue();
    }

    @NotNull
    private static Type<?> getWrappedType(NamedNode<?> objectTypeDefinition, FieldDefinition fieldDefinition) {
        var fieldType = fieldDefinition.getType();
        if (fieldType instanceof NonNullType) {
            fieldType = ((NonNullType) fieldType).getType();
        }

        if (!(fieldType instanceof ListType)) {
            throw new IllegalArgumentException(String.format("The field %s.%s is not a list type, this is not supported for relay connections.", objectTypeDefinition.getName(), fieldDefinition.getName()));
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
    private static TypeName maybeCreateConnectionType(
            String connectionTypeName,
            TypeDefinitionRegistry typeDefinitionRegistry,
            FieldDefinition fieldDefinition,
            TypeName wrappedType,
            boolean nodesFieldInConnectionsEnabled,
            boolean totalCountFieldInConnectionsEnabled,
            List<Directive> directives
    ) {
        // 1. Opprett og legg til Connection- og Edge-typene i typeDefinitionRegistry
        //    dersom Connection-typen ikke allerede er definert.

        var connectionType = new TypeName(connectionTypeName);
        var source = fieldDefinition.getSourceLocation();
        if (!typeDefinitionRegistry.hasType(connectionType)) {
            var edgeType = new TypeName(connectionTypeName + SCHEMA_EDGE_SUFFIX.getName());
            var edge = ObjectTypeDefinition.newObjectTypeDefinition()
                    .name(edgeType.getName())
                    .directives(directives)
                    .fieldDefinitions(List.of(
                            FieldDefinition.newFieldDefinition()
                                    .name(CONNECTION_CURSOR_FIELD.getName())
                                    .type(STRING_TYPE)
                                    .sourceLocation(source)
                                    .build(),
                            FieldDefinition.newFieldDefinition()
                                    .name(CONNECTION_NODE_FIELD.getName())
                                    .type(wrappedType)
                                    .sourceLocation(source)
                                    .build()
                    ))
                    .sourceLocation(source)
                    .build();

            var fieldsInConnectionType = new ArrayList<>(List.of(
                    FieldDefinition.newFieldDefinition()
                            .name(CONNECTION_EDGE_FIELD.getName())
                            .type(new ListType(edgeType))
                            .sourceLocation(source)
                            .build(),
                    FieldDefinition.newFieldDefinition()
                            .name(CONNECTION_PAGE_INFO_FIELD.getName())
                            .type(PAGE_INFO)
                            .sourceLocation(source)
                            .build()
            ));

            if (nodesFieldInConnectionsEnabled) {
                fieldsInConnectionType.add(
                        FieldDefinition.newFieldDefinition()
                                .name(CONNECTION_NODES_FIELD.getName())
                                .type(fieldDefinition.getType())
                                .sourceLocation(source)
                                .build()
                );
            }
            if (totalCountFieldInConnectionsEnabled) {
                fieldsInConnectionType.add(
                        FieldDefinition.newFieldDefinition()
                                .name(CONNECTION_TOTAL_COUNT.getName())
                                .type(INT_TYPE)
                                .sourceLocation(source)
                                .build()
                );
            }

            var connection = ObjectTypeDefinition.newObjectTypeDefinition()
                    .name(connectionTypeName)
                    .fieldDefinitions(fieldsInConnectionType)
                    .sourceLocation(source)
                    .directives(directives)
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
