package no.sikt.graphitron.definitions.fields;

import graphql.language.ObjectField;
import graphql.language.*;
import no.sikt.graphitron.configuration.externalreferences.CodeReference;
import no.sikt.graphitron.definitions.fields.containedtypes.FieldReference;
import no.sikt.graphitron.definitions.fields.containedtypes.FieldType;
import no.sikt.graphitron.definitions.fields.containedtypes.MutationType;
import no.sikt.graphitron.definitions.interfaces.GenerationField;
import no.sikt.graphitron.definitions.mapping.JOOQMapping;
import no.sikt.graphitron.definitions.mapping.MethodMapping;
import no.sikt.graphitron.definitions.sql.SQLCondition;
import no.sikt.graphql.directives.DirectiveHelpers;
import no.sikt.graphql.directives.GenerationDirective;
import no.sikt.graphql.directives.GenerationDirectiveParam;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static no.sikt.graphitron.generators.codebuilding.NameFormat.toCamelCase;
import static no.sikt.graphql.directives.DirectiveHelpers.*;
import static no.sikt.graphql.directives.GenerationDirective.SERVICE;
import static no.sikt.graphql.directives.GenerationDirective.*;
import static no.sikt.graphql.directives.GenerationDirectiveParam.*;
import static no.sikt.graphql.naming.GraphQLReservedName.SCHEMA_MUTATION;
import static no.sikt.graphql.naming.GraphQLReservedName.SCHEMA_QUERY;

/**
 * This class represents the general functionality associated with GraphQLs fields that can initialise code generation.
 */
public abstract class GenerationSourceField<T extends NamedNode<T> & DirectivesContainer<T>> extends AbstractField<T> implements GenerationField {
    private final boolean isGenerated, isResolver, isGeneratedAsResolver, isExternalField, hasFieldDirective;
    private final List<FieldReference> fieldReferences;
    private final SQLCondition condition;
    private final MethodMapping mappingForRecordFieldOverride;
    private final CodeReference serviceReference;

    public GenerationSourceField(T field, FieldType fieldType, String container) {
        super(field, fieldType, container);
        fieldReferences = new ArrayList<>();
        if (field.hasDirective(REFERENCE.getName())) {
            var referenceDirective = field.getDirectives(REFERENCE.getName()).get(0);

            Optional.ofNullable(referenceDirective.getArgument(REFERENCES.getName()))
                    .map(Argument::getValue)
                    .ifPresent(this::addFieldReferences);
        }

        if (field.hasDirective(GenerationDirective.CONDITION.getName()) && fieldType != null) {
            condition = new SQLCondition(
                    new CodeReference(field, GenerationDirective.CONDITION, GenerationDirectiveParam.CONDITION, getName()),
                    getOptionalDirectiveArgumentBoolean(field, GenerationDirective.CONDITION, OVERRIDE).orElse(false)
            );
        } else {
            condition = null;
        }

        serviceReference = field.hasDirective(SERVICE.getName()) ? new CodeReference(field, SERVICE, GenerationDirectiveParam.SERVICE, field.getName()) : null;
        if (field.hasDirective(FIELD.getName())) {
            mappingForRecordFieldOverride = getJavaName().isEmpty() ? new MethodMapping(toCamelCase(getUpperCaseName())) : new MethodMapping(getJavaName());
        } else {
            mappingForRecordFieldOverride = getMappingFromFieldOverride();
        }

        hasFieldDirective = field.hasDirective(FIELD.getName());
        isExternalField = field.hasDirective(EXTERNAL_FIELD.getName());
        isGenerated = !field.hasDirective(NOT_GENERATED.getName());
        isResolver = field.hasDirective(SPLIT_QUERY.getName())
                || (field instanceof FieldDefinition && !container.equals(SCHEMA_QUERY.getName()) && !((FieldDefinition) field).getInputValueDefinitions().isEmpty());

        isGeneratedAsResolver = (isResolver || container.equals(SCHEMA_QUERY.getName()) || container.equals(SCHEMA_MUTATION.getName())) && isGenerated;
    }

    /**
     * Construct references from the {@link GenerationDirectiveParam#REFERENCES references} parameter.
     */
    private void addFieldReferences(Value<?> referencesValue) {
        var values = referencesValue instanceof ArrayValue ? ((ArrayValue) referencesValue).getValues() : List.of(((ObjectValue) referencesValue));

        values.stream()
                .filter(value -> value instanceof ObjectValue)
                .forEach(value -> {
                    var objectFields = ((ObjectValue) value).getObjectFields();
                    var table = getOptionalObjectFieldByName(objectFields, GenerationDirectiveParam.TABLE);
                    var key = getOptionalObjectFieldByName(objectFields, KEY);
                    var referencedCondition = getOptionalObjectFieldByName(objectFields, GenerationDirectiveParam.CONDITION)
                            .map(ObjectField::getValue)
                            .map(it -> (ObjectValue) it)
                            .map(ObjectValue::getObjectFields)
                            .map(fields -> new SQLCondition(new CodeReference(fields, getName()))).orElse(null);

                    fieldReferences.add(
                            new FieldReference(
                                    table.map(DirectiveHelpers::stringValueOf).map(JOOQMapping::fromTable).orElse(null),
                                    key.map(DirectiveHelpers::stringValueOf).map(JOOQMapping::fromKey).orElse(null),
                                    referencedCondition
                            )
                    );
                });
    }

    @Override
    public boolean isResolver() {
        return isResolver;
    }

    public boolean isExternalField() {
        return isExternalField;
    }

    public boolean hasFieldDirective() {
        return hasFieldDirective;
    }

    @Override
    public MethodMapping getMappingForRecordFieldOverride() {
        return mappingForRecordFieldOverride;
    }

    @Override
    public String getFieldRecordMappingName() {
        return mappingForRecordFieldOverride.getName();
    }

    @Override
    public List<FieldReference> getFieldReferences() {
        return fieldReferences;
    }

    @Override
    public boolean hasFieldReferences() {
        return !fieldReferences.isEmpty();
    }

    @Override
    public SQLCondition getCondition() {
        return condition;
    }

    @Override
    public boolean hasCondition() {
        return condition != null;
    }

    @Override
    public boolean hasOverridingCondition() {
        return hasCondition() && condition.isOverride();
    }

    @Override
    public boolean isGenerated() {
        return isGenerated;
    }

    @Override
    public boolean hasServiceReference() {
        return serviceReference != null;
    }

    @Override
    public CodeReference getServiceReference() {
        return serviceReference;
    }

    @Override
    public boolean hasMutationType() {
        return false;
    }

    @Override
    public MutationType getMutationType() {
        return null;
    }

    @Override
    public boolean isGeneratedWithResolver() {
        return isGeneratedAsResolver;
    }

    @Override
    public boolean isExplicitlyNotGenerated() {
        return !isGenerated;
    }
}
