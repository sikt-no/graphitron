package no.fellesstudentsystem.graphitron.definitions.fields;

import graphql.language.ObjectField;
import graphql.language.*;
import no.fellesstudentsystem.graphitron.definitions.mapping.JOOQTableMapping;
import no.fellesstudentsystem.graphitron.definitions.mapping.MethodMapping;
import no.fellesstudentsystem.graphitron.configuration.externalreferences.CodeReference;
import no.fellesstudentsystem.graphitron.definitions.sql.SQLCondition;
import no.fellesstudentsystem.graphitron.definitions.sql.SQLImplicitFKJoin;
import no.fellesstudentsystem.graphql.directives.DirectiveHelpers;
import no.fellesstudentsystem.graphql.directives.GenerationDirective;
import no.fellesstudentsystem.graphql.directives.GenerationDirectiveParam;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static no.fellesstudentsystem.graphql.directives.GenerationDirective.COLUMN;
import static no.fellesstudentsystem.graphql.directives.GenerationDirective.REFERENCE;
import static no.fellesstudentsystem.graphql.directives.GenerationDirectiveParam.*;
import static no.fellesstudentsystem.graphql.directives.DirectiveHelpers.*;

/**
 * This class represents the general functionality associated with GraphQLs object fields.
 */
public abstract class AbstractField {
    private final FieldType fieldType;
    private final String name, upperCaseName, unprocessedNameInput;
    private final List<FieldReference> fieldReferences;
    private final SQLCondition condition;
    private final MethodMapping mappingFromFieldName, mappingFromColumn;

    private <T extends NamedNode<T> & DirectivesContainer<T>> AbstractField(T field, FieldType fieldType) {
        name = field.getName();
        if (field.hasDirective(COLUMN.getName())) {
            var columnValue = getDirectiveArgumentString(field, COLUMN, NAME);
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
                    .anyMatch(it -> it.equals(TABLE.getName()) || it.equals(GenerationDirectiveParam.CONDITION.getName()) || it.equals(KEY.getName()))) {
                fieldReferences.add(new FieldReference(field));
            } else if (fieldType != null && !fieldReferencesContainsReferredFieldType(fieldType)) {
                fieldReferences.add(new FieldReference(new JOOQTableMapping(fieldType.getName()), "", null));
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
                    var table = getObjectFieldByName(objectFields, TABLE);
                    var key = getOptionalObjectFieldByName(objectFields, KEY);
                    var referencedCondition = getOptionalObjectFieldByName(objectFields, GenerationDirectiveParam.CONDITION)
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
                                    new JOOQTableMapping(stringValueOf(table)),
                                    key.map(DirectiveHelpers::stringValueOf).orElse(""),
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
     * @return Is this field wrapped in a list?
     */
    public boolean isIterableWrapped() {
        return fieldType.isIterableWrapped();
    }

    /**
     * @return The FieldType object containing information about the underlying data type and its nullability and wrapping configuration.
     */
    public FieldType getFieldType() {
        return fieldType;
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

    /**
     * Create a join that is limited to a single field.
     */
    @Nullable
    protected static <T extends NamedNode<T> & DirectivesContainer<T>> SQLImplicitFKJoin getSqlColumnJoin(T field) {
        return getOptionalDirectiveArgumentString(field, COLUMN, TABLE)
                .map(table -> new SQLImplicitFKJoin(
                        table,
                        getOptionalDirectiveArgumentString(field, COLUMN, KEY).orElse(""))
                )
                .orElse(null);
    }

    private boolean fieldReferencesContainsReferredFieldType(FieldType referringFieldType) {
        return fieldReferences.stream()
                .map(FieldReference::getTable)
                .map(JOOQTableMapping::getName)
                .anyMatch(tableName -> tableName.equalsIgnoreCase(referringFieldType.getName()));
    }
}
