package no.sikt.graphitron.definitions.objects;

import graphql.language.InterfaceTypeDefinition;
import no.sikt.graphitron.definitions.fields.ObjectField;
import no.sikt.graphitron.definitions.interfaces.TypeResolverTarget;
import no.sikt.graphitron.definitions.mapping.JOOQMapping;

import java.util.List;

import static no.sikt.graphql.directives.GenerationDirective.NOT_GENERATED;

/**
 * Represents a GraphQL interface.
 */
public class InterfaceDefinition extends AbstractObjectDefinition<InterfaceTypeDefinition, ObjectField> implements TypeResolverTarget {
    private final boolean isGenerated;

    public InterfaceDefinition(InterfaceTypeDefinition typeDefinition) {
        super(typeDefinition);
        isGenerated = !typeDefinition.hasDirective(NOT_GENERATED.getName());
    }

    @Override
    protected List<ObjectField> createFields(InterfaceTypeDefinition objectDefinition) {
        return ObjectField.from(objectDefinition.getFieldDefinitions(), getName());
    }

    public JOOQMapping getTable() {
        return null;
    }

    public boolean hasTable() {
        return false;
    }

    public boolean hasDiscrimatingField() {
        return false;
    }

    public String getDiscriminatingFieldName() {
        return null;
    }

    @Override
    public boolean isGenerated() {
        return isGenerated;
    }

    @Override
    public boolean isGeneratedWithResolver() {
        return isGenerated;
    }

    @Override
    public boolean isExplicitlyNotGenerated() {
        return !isGenerated;
    }
}
