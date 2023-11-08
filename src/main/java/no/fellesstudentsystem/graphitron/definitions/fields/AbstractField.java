package no.fellesstudentsystem.graphitron.definitions.fields;

import graphql.language.ObjectField;
import graphql.language.*;
import no.fellesstudentsystem.graphitron.configuration.externalreferences.CodeReference;
import no.fellesstudentsystem.graphitron.definitions.mapping.JOOQMapping;
import no.fellesstudentsystem.graphitron.definitions.mapping.MethodMapping;
import no.fellesstudentsystem.graphitron.definitions.sql.SQLCondition;
import no.fellesstudentsystem.graphql.directives.DirectiveHelpers;
import no.fellesstudentsystem.graphql.directives.GenerationDirective;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static no.fellesstudentsystem.graphql.directives.DirectiveHelpers.*;
import static no.fellesstudentsystem.graphql.directives.GenerationDirective.*;
import static no.fellesstudentsystem.graphql.directives.GenerationDirectiveParam.CONDITION;
import static no.fellesstudentsystem.graphql.directives.GenerationDirectiveParam.TABLE;
import static no.fellesstudentsystem.graphql.directives.GenerationDirectiveParam.*;

/**
 * This class represents the general functionality associated with GraphQLs object fields.
 */
public abstract class AbstractField {
    private final FieldType fieldType;
    private final String name, upperCaseName, unprocessedNameInput;
    private final boolean isGenerated;
    private final List<FieldReference> fieldReferences;
    private final SQLCondition condition;
    private final MethodMapping mappingFromFieldName, mappingFromColumn;

    private <T extends NamedNode<T> & DirectivesContainer<T>> AbstractField(T field, FieldType fieldType) {
        name = field.getName();
        if (field.hasDirective(FIELD.getName())) {
            var columnValue = getDirectiveArgumentString(field, FIELD, NAME);
            unprocessedNameInput = fieldType != null && fieldType.isID() ? columnValue.toLowerCase() : columnValue;
            upperCaseName = columnValue.toUpperCase();
        } else {
            unprocessedNameInput = name;
            upperCaseName = name.toUpperCase();
        }

        fieldReferences = new ArrayList<>();
        if (field.hasDirective(REFERENCE.getName())) {
            var refrenceDirective = field.getDirectives(REFERENCE.getName()).get(0);

            Optional.ofNullable(refrenceDirective.getArgument(VIA.getName()))
                    .map(Argument::getValue)
                    .ifPresent(this::addViaFieldReferences);

            if (refrenceDirective.getArguments().stream()
                    .map(Argument::getName)
                    .anyMatch(it -> it.equals(TABLE.getName()) || it.equals(CONDITION.getName()) || it.equals(KEY.getName()))) {
                fieldReferences.add(new FieldReference(field));
            }
        }

        if (field.hasDirective(GenerationDirective.CONDITION.getName()) && fieldType != null) {
            condition = new SQLCondition(
                    new CodeReference(field, GenerationDirective.CONDITION, CONDITION, name),
                    getOptionalDirectiveArgumentBoolean(field, GenerationDirective.CONDITION, OVERRIDE).orElse(false)
            );
        } else {
            condition = null;
        }
        this.fieldType = fieldType;
        mappingFromFieldName = new MethodMapping(name);
        mappingFromColumn = new MethodMapping(unprocessedNameInput);
        isGenerated = !field.hasDirective(NOT_GENERATED.getName());
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
                    var table = getOptionalObjectFieldByName(objectFields, TABLE);
                    var key = getOptionalObjectFieldByName(objectFields, KEY);
                    var referencedCondition = getOptionalObjectFieldByName(objectFields, CONDITION)
                            .map(ObjectField::getValue)
                            .map(it -> (ObjectValue) it)
                            .map(ObjectValue::getObjectFields)
                            .map(fields ->
                                    new SQLCondition(
                                            new CodeReference(
                                                    stringValueOf(getObjectFieldByName(fields, NAME)),
                                                    getOptionalObjectFieldByName(fields, METHOD).map(DirectiveHelpers::stringValueOf).orElse(name)
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

    public AbstractField(FieldDefinition field) {
        this(field, new FieldType(field.getType()));
    }

    public AbstractField(EnumValueDefinition field) {
        this(field, null);
    }

    public AbstractField(InputValueDefinition field) {
        this(field, new FieldType(field.getType()));
    }

    /**
     * @return The name of the field's underlying data type.
     */
    public String getTypeName() {
        return fieldType.getName();
    }

    /**
     * @return Is this field an ID?
     */
    public boolean isID() {
        return fieldType.isID();
    }

    /**
     * @return Is this field wrapped in a list?
     */
    public boolean isIterableWrapped() {
        return fieldType.isIterableWrapped();
    }

    /**
     * @return Is this field optional/nullable?
     */
    public boolean isNullable() {
        return fieldType.isIterableWrapped() ? fieldType.isIterableNullable() : fieldType.isNullable();
    }

    /**
     * @return Is this field required/non-nullable?
     */
    public boolean isNonNullable() {
        return fieldType.isIterableWrapped() ? !fieldType.isIterableNullable() : !fieldType.isNullable();
    }

    /**
     * @return {@link com.squareup.javapoet.TypeName} for this field's type.
     */
    public com.squareup.javapoet.TypeName getTypeClass() {
        return fieldType.getTypeClass();
    }

    public String getName() {
        return name;
    }

    /**
     * @return The database equivalent name of this field. Defaults to field name.
     */
    public String getUpperCaseName() {
        return upperCaseName;
    }

    public String getUnprocessedNameInput() {
        return unprocessedNameInput;
    }

    /**
     * @return Schema-side method name mappings based on the GraphQL equivalent of this field.
     */
    public MethodMapping getMappingFromFieldName() {
        return mappingFromFieldName;
    }

    /**
     * @return Schema-side method name mappings based on the GraphQL equivalent of this field.
     */
    public MethodMapping getMappingFromColumn() {
        return mappingFromColumn;
    }

    public List<FieldReference> getFieldReferences() {
        return fieldReferences;
    }

    public boolean hasFieldReferences() {
        return !fieldReferences.isEmpty();
    }

    public SQLCondition getCondition() {
        return condition;
    }

    public boolean hasCondition() {
        return condition != null;
    }

    public boolean hasOverridingCondition() {
        return hasCondition() && condition.isOverride();
    }

    public boolean isGenerated() {
        return isGenerated;
    }

    /**
     * @return Does this field point to a resolver method?
     */
    public boolean isResolver() {
        return false;
    }

    /**
     * @return Is this object field a Query or Mutation root field?
     */
    public boolean isRootField() {
        return false;
    }
}
