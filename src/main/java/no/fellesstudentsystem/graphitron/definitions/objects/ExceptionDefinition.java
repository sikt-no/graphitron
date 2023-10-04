package no.fellesstudentsystem.graphitron.definitions.objects;

import graphql.language.ObjectTypeDefinition;
import no.fellesstudentsystem.graphitron.configuration.externalreferences.CodeReference;
import no.fellesstudentsystem.graphitron.definitions.fields.ObjectField;
import no.fellesstudentsystem.graphql.directives.GenerationDirectiveParam;

import java.util.List;
import java.util.stream.Collectors;

import static no.fellesstudentsystem.graphql.directives.GenerationDirective.*;

/**
 * Represents the default GraphQL object, parsed as an exception type.
 */
public class ExceptionDefinition extends AbstractObjectDefinition<ObjectTypeDefinition> {
    private final boolean isGenerated;
    private final List<ObjectField> objectFields;
    private final CodeReference exceptionReference;

    public ExceptionDefinition(ObjectTypeDefinition objectDefinition) {
        super(objectDefinition);

        objectFields = ObjectField.from(objectDefinition.getFieldDefinitions());

        isGenerated = getFields().stream().anyMatch(ObjectField::isGenerated);

        exceptionReference = objectDefinition.hasDirective(ERROR.getName()) ? new CodeReference(objectDefinition, ERROR, GenerationDirectiveParam.ERROR, objectDefinition.getName()) : null;
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
     * @return The reference to the Java exception that this schema object is related to.
     */
    public CodeReference getExceptionReference() {
        return exceptionReference;
    }

    /**
     * @return Does this object have a reference to a java exception defined?
     */
    public boolean hasExceptionReference() {
        return exceptionReference != null;
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
