package no.sikt.graphitron.definitions.objects;

import com.palantir.javapoet.ClassName;
import graphql.language.InterfaceTypeDefinition;
import no.sikt.graphitron.definitions.fields.ObjectField;
import no.sikt.graphitron.definitions.interfaces.GenerationTarget;
import no.sikt.graphitron.definitions.interfaces.RecordObjectSpecification;
import no.sikt.graphitron.definitions.keys.EntityKeySet;
import no.sikt.graphitron.definitions.mapping.JOOQMapping;
import no.sikt.graphql.directives.GenerationDirective;

import static no.sikt.graphql.directives.DirectiveHelpers.getDirectiveArgumentString;
import static no.sikt.graphql.directives.DirectiveHelpers.getOptionalDirectiveArgumentString;
import static no.sikt.graphql.directives.GenerationDirective.NOT_GENERATED;
import static no.sikt.graphql.directives.GenerationDirectiveParam.NAME;
import static no.sikt.graphql.directives.GenerationDirectiveParam.ON;

public class InterfaceObjectDefinition extends InterfaceDefinition implements RecordObjectSpecification<ObjectField> {


    private final JOOQMapping table;
    private final boolean hasTable, hasDiscrimate, hasResolvers, isGenerated, explicitlyNotGenerated;
    private final String discriminatingColumn;

    public InterfaceObjectDefinition(InterfaceTypeDefinition typeDefinition) {
        super(typeDefinition);
        hasTable = typeDefinition.hasDirective(GenerationDirective.TABLE.getName());
        table = hasTable
                ? JOOQMapping.fromTable(getOptionalDirectiveArgumentString(typeDefinition, GenerationDirective.TABLE, NAME).orElse(getName().toUpperCase()))
                : null;

        hasDiscrimate = typeDefinition.hasDirective(GenerationDirective.DISCRIMINATE.getName());
        discriminatingColumn = hasDiscrimate ? getDirectiveArgumentString(typeDefinition, GenerationDirective.DISCRIMINATE, ON) : null;

        hasResolvers = getFields().stream().anyMatch(GenerationTarget::isGeneratedWithResolver);
        isGenerated = getFields().stream().anyMatch(GenerationTarget::isGenerated);
        explicitlyNotGenerated = typeDefinition.hasDirective(NOT_GENERATED.getName());
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
        return explicitlyNotGenerated;
    }

    public JOOQMapping getTable() {
        return table;
    }

    public boolean hasTable() {
        return hasTable;
    }

    public boolean hasDiscrimatingField() {
        return hasDiscrimate;
    }

    public String getDiscriminatingFieldName() {
        return discriminatingColumn;
    }
}
