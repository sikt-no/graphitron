package no.sikt.graphitron.definitions.objects;

import com.palantir.javapoet.ClassName;
import graphql.language.InterfaceTypeDefinition;
import no.sikt.graphitron.definitions.fields.ObjectField;
import no.sikt.graphitron.definitions.interfaces.GenerationTarget;
import no.sikt.graphitron.definitions.interfaces.RecordObjectSpecification;
import no.sikt.graphitron.definitions.interfaces.TypeResolverTarget;
import no.sikt.graphitron.definitions.keys.EntityKeySet;
import no.sikt.graphitron.definitions.mapping.JOOQMapping;
import no.sikt.graphql.directives.GenerationDirective;

import java.util.List;

import static no.sikt.graphql.directives.DirectiveHelpers.getDirectiveArgumentString;
import static no.sikt.graphql.directives.DirectiveHelpers.getOptionalDirectiveArgumentString;
import static no.sikt.graphql.directives.GenerationDirective.NOT_GENERATED;
import static no.sikt.graphql.directives.GenerationDirectiveParam.NAME;
import static no.sikt.graphql.directives.GenerationDirectiveParam.ON;

/**
 * Represents a GraphQL interface.
 */
public class InterfaceDefinition extends AbstractObjectDefinition<InterfaceTypeDefinition, ObjectField> implements TypeResolverTarget, RecordObjectSpecification<ObjectField> {
    private final JOOQMapping table;
    private final boolean isGenerated, hasTable, hasDiscriminator, hasResolvers;
    private final String discriminatorFieldName;

    public InterfaceDefinition(InterfaceTypeDefinition typeDefinition) {
        super(typeDefinition);
        isGenerated = !typeDefinition.hasDirective(NOT_GENERATED.getName());
        hasTable = typeDefinition.hasDirective(GenerationDirective.TABLE.getName());
        table = hasTable
                ? JOOQMapping.fromTable(getOptionalDirectiveArgumentString(typeDefinition, GenerationDirective.TABLE, NAME).orElse(getName().toUpperCase()))
                : null;

        hasDiscriminator = typeDefinition.hasDirective(GenerationDirective.DISCRIMINATE.getName());
        discriminatorFieldName = hasDiscriminator ? getDirectiveArgumentString(typeDefinition, GenerationDirective.DISCRIMINATE, ON) : null;
        hasResolvers = getFields().stream().anyMatch(GenerationTarget::isGeneratedWithResolver);
    }

    @Override
    protected List<ObjectField> createFields(InterfaceTypeDefinition objectDefinition) {
        return ObjectField.from(objectDefinition.getFieldDefinitions(), getName());
    }

    public boolean hasTable() {
        return hasTable;
    }

    public boolean isMultiTableInterface() {
        return !hasTable;
    }

    public JOOQMapping getTable() {
        return table;
    }

    public boolean hasDiscriminator() {
        return hasDiscriminator;
    }

    public String getDiscriminatorFieldName() {
        return discriminatorFieldName;
    }

    @Override
    public Class<?> getRecordReference() {
        return null;
    }

    @Override
    public String getRecordReferenceName() {
        return null;
    }

    @Override
    public ClassName getRecordClassName() {
        return null;
    }

    @Override
    public boolean hasJavaRecordReference() {
        return false;
    }

    @Override
    public boolean hasRecordReference() {
        return false;
    }

    @Override
    public ClassName asSourceClassName(boolean toRecord) {
        return null;
    }

    @Override
    public ClassName asTargetClassName(boolean toRecord) {
        return null;
    }

    @Override
    public String asRecordName() {
        return null;
    }

    @Override
    public boolean isEntity() {
        return false;
    }

    @Override
    public EntityKeySet getEntityKeys() {
        return null;
    }

    @Override
    public boolean isGenerated() {
        return isGenerated;
    }

    @Override
    public boolean isGeneratedWithResolver() {
        return hasResolvers;
    }

    @Override
    public boolean isExplicitlyNotGenerated() {
        return !isGenerated;
    }
}
