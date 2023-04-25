package no.fellesstudentsystem.graphitron.definitions.objects;

import graphql.language.ObjectTypeDefinition;
import no.fellesstudentsystem.graphitron.definitions.fields.ObjectField;

import java.util.List;
import java.util.stream.Collectors;

import static no.fellesstudentsystem.graphql.mapping.GenerationDirective.*;
import static no.fellesstudentsystem.graphql.mapping.GraphQLDirectiveParam.NAME;
import static no.fellesstudentsystem.graphql.schema.SchemaHelpers.getDirectiveArgumentString;

/**
 * Represents the default GraphQL object, parsed as an exception type.
 */
public class ExceptionDefinition extends AbstractObjectDefinition<ObjectTypeDefinition> {
    private final boolean isGenerated;
    private final List<ObjectField> objectFields;
    private final String exceptionReference;

    public ExceptionDefinition(ObjectTypeDefinition objectDefinition) {
        super(objectDefinition);

        objectFields = ObjectField.from(objectDefinition.getFieldDefinitions());

        isGenerated = getFields().stream().anyMatch(ObjectField::isGenerated);

        exceptionReference = objectDefinition.hasDirective(ERROR.getName()) ? getDirectiveArgumentString(objectDefinition, ERROR, ERROR.getParamName(NAME)) : "";
    }

    /**
     * @return The exact name of the database table that this object corresponds to.
     */
    public List<ObjectField> getFields() {
        return objectFields;
    }

    public boolean isGenerated() {
        return isGenerated;
    }

    /**
     * @return The name of the java exception that this object is related to.
     */
    public String getExceptionReference() {
        return exceptionReference;
    }

    /**
     * @return Does this object have a reference to a java exception defined?
     */
    public boolean hasExceptionReference() {
        return !exceptionReference.isEmpty();
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
}
