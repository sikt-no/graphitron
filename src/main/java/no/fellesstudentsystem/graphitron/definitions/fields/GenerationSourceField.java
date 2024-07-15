package no.fellesstudentsystem.graphitron.definitions.fields;

import graphql.language.ObjectField;
import graphql.language.*;
import no.fellesstudentsystem.graphitron.configuration.externalreferences.CodeReference;
import no.fellesstudentsystem.graphitron.definitions.fields.containedtypes.FieldReference;
import no.fellesstudentsystem.graphitron.definitions.fields.containedtypes.FieldType;
import no.fellesstudentsystem.graphitron.definitions.fields.containedtypes.MutationType;
import no.fellesstudentsystem.graphitron.definitions.interfaces.GenerationField;
import no.fellesstudentsystem.graphitron.definitions.mapping.JOOQMapping;
import no.fellesstudentsystem.graphitron.definitions.mapping.MethodMapping;
import no.fellesstudentsystem.graphitron.definitions.sql.SQLCondition;
import no.fellesstudentsystem.graphql.directives.DirectiveHelpers;
import no.fellesstudentsystem.graphql.directives.GenerationDirective;
import no.fellesstudentsystem.graphql.directives.GenerationDirectiveParam;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static no.fellesstudentsystem.graphitron.generators.codebuilding.NameFormat.toCamelCase;
import static no.fellesstudentsystem.graphql.directives.DirectiveHelpers.*;
import static no.fellesstudentsystem.graphql.directives.GenerationDirective.SERVICE;
import static no.fellesstudentsystem.graphql.directives.GenerationDirective.*;
import static no.fellesstudentsystem.graphql.directives.GenerationDirectiveParam.*;
import static no.fellesstudentsystem.graphql.naming.GraphQLReservedName.SCHEMA_MUTATION;
import static no.fellesstudentsystem.graphql.naming.GraphQLReservedName.SCHEMA_QUERY;

/**
 * This class represents the general functionality associated with GraphQLs fields that can initialise code generation.
 */
public abstract class GenerationSourceField<T extends NamedNode<T> & DirectivesContainer<T>> extends AbstractField<T> implements GenerationField {
    private final boolean isGenerated, isResolver, isGeneratedAsResolver;
    private final List<FieldReference> fieldReferences;
    private final SQLCondition condition;
    private final MethodMapping mappingForRecordFieldOverride;
    private final CodeReference serviceReference;

    public GenerationSourceField(T field, FieldType fieldType, String container) {
        super(field, fieldType, container);
        fieldReferences = new ArrayList<>();
        if (field.hasDirective(REFERENCE.getName())) {
            var refrenceDirective = field.getDirectives(REFERENCE.getName()).get(0);

            Optional.ofNullable(refrenceDirective.getArgument(VIA.getName()))
                    .map(Argument::getValue)
                    .ifPresent(this::addViaFieldReferences);

            if (refrenceDirective.getArguments().stream()
                    .map(Argument::getName)
                    .anyMatch(it -> it.equals(GenerationDirectiveParam.TABLE.getName()) || it.equals(GenerationDirectiveParam.CONDITION.getName()) || it.equals(KEY.getName()))) {
                fieldReferences.add(new FieldReference(field));
            }
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
        isGenerated = !field.hasDirective(NOT_GENERATED.getName());
        isResolver = field.hasDirective(SPLIT_QUERY.getName());
        isGeneratedAsResolver = (isResolver || container.equals(SCHEMA_QUERY.getName()) || container.equals(SCHEMA_MUTATION.getName())) && isGenerated;
    }

    /**
     * Construct references from the {@link no.fellesstudentsystem.graphql.directives.GenerationDirectiveParam#VIA via} parameter.
     */
    private void addViaFieldReferences(Value<?> viaValue) {
        var values = viaValue instanceof ArrayValue ? ((ArrayValue) viaValue).getValues() : List.of(((ObjectValue) viaValue));

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
                            .map(fields ->
                                    new SQLCondition(
                                            new CodeReference(
                                                    stringValueOf(getObjectFieldByName(fields, NAME)),
                                                    getOptionalObjectFieldByName(fields, METHOD).map(DirectiveHelpers::stringValueOf).orElse(getName())
                                            )
                                    )
                            ).orElse(null);

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
