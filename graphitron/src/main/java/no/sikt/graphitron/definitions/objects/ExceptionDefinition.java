package no.sikt.graphitron.definitions.objects;

import graphql.language.*;
import no.sikt.graphitron.configuration.ErrorHandlerType;
import no.sikt.graphitron.configuration.ExceptionToErrorMapping;
import no.sikt.graphitron.definitions.fields.ObjectField;
import no.sikt.graphql.directives.DirectiveHelpers;
import org.jooq.exception.DataAccessException;

import java.util.List;
import java.util.stream.Collectors;

import static no.sikt.graphql.directives.DirectiveHelpers.getObjectFieldByName;
import static no.sikt.graphql.directives.DirectiveHelpers.getOptionalObjectFieldByName;
import static no.sikt.graphql.directives.GenerationDirective.ERROR;
import static no.sikt.graphql.directives.GenerationDirectiveParam.*;

/**
 * Represents the default GraphQL object, parsed as an exception type.
 */
public class ExceptionDefinition extends AbstractObjectDefinition<ObjectTypeDefinition, ObjectField> {
    private final boolean isGenerated;
    private final List<ExceptionToErrorMapping> exceptionToErrorMappings;

    public ExceptionDefinition(ObjectTypeDefinition objectDefinition) {
        super(objectDefinition);

        isGenerated = getFields().stream().anyMatch(ObjectField::isGeneratedWithResolver);

        if (objectDefinition.hasDirective(ERROR.getName())) {
            var refrenceDirective = objectDefinition.getDirectives(ERROR.getName()).get(0);
            var handlersArgument = refrenceDirective.getArgument(HANDLERS.getName());
            exceptionToErrorMappings = getErrorMappings(handlersArgument.getValue(), objectDefinition);
        } else {
            exceptionToErrorMappings = List.of();
        }
    }

    private static List<ExceptionToErrorMapping> getErrorMappings(Value handlersValue, ObjectTypeDefinition objectDefinition) {
        var values = handlersValue instanceof ArrayValue ? ((ArrayValue) handlersValue).getValues() : List.of(((ObjectValue) handlersValue));

        return values.stream()
                .filter(value -> value instanceof ObjectValue)
                .map(value -> {
                    var objectFields = ((ObjectValue) value).getObjectFields();
                    ErrorHandlerType handler = ErrorHandlerType.valueOf(((EnumValue) getObjectFieldByName(objectFields, HANDLER).getValue()).getName());
                    return new ExceptionToErrorMapping(
                            handler,
                            getOptionalObjectFieldByName(objectFields, CLASS_NAME).map(DirectiveHelpers::stringValueOf).orElseGet(
                                    () -> {
                                        if (handler == ErrorHandlerType.DATABASE) {
                                            return DataAccessException.class.getName();
                                        }
                                        throw new IllegalArgumentException("'" + CLASS_NAME.getName() + "´ directive argument must be defined for error handler of type " + handler);
                                    }),
                            objectDefinition.getName(),
                            getOptionalObjectFieldByName(objectFields, CODE).map(DirectiveHelpers::stringValueOf).orElse(null),
                            getOptionalObjectFieldByName(objectFields, MATCHES).map(DirectiveHelpers::stringValueOf).orElse(null),
                            getOptionalObjectFieldByName(objectFields, DESCRIPTION).map(DirectiveHelpers::stringValueOf).orElse(null)
                    );
                }).collect(Collectors.toList());

    }

    @Override
    protected List<ObjectField> createFields(ObjectTypeDefinition objectDefinition) {
        return ObjectField.from(objectDefinition.getFieldDefinitions(), getName());
    }

    public boolean isGenerated() {
        return isGenerated;
    }

    /**
     * Creates instances of this class for each of the {@link ObjectTypeDefinition} provided.
     * @return List of ObjectDefinitions.
     */
    public static List<ExceptionDefinition> processObjectDefinitions(List<ObjectTypeDefinition> objects) {
        return objects
                .stream()
                .map(ExceptionDefinition::new)
                .collect(Collectors.toList());
    }

    public List<ExceptionToErrorMapping> getExceptionToErrorMappings() {
        return exceptionToErrorMappings;
    }
}
