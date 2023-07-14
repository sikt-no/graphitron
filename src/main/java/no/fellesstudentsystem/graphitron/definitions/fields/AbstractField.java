package no.fellesstudentsystem.graphitron.definitions.fields;

import graphql.language.ObjectField;
import graphql.language.*;
import no.fellesstudentsystem.graphitron.definitions.mapping.JOOQTableMapping;
import no.fellesstudentsystem.graphitron.definitions.mapping.MethodMapping;
import no.fellesstudentsystem.graphitron.definitions.sql.SQLCondition;
import no.fellesstudentsystem.graphitron.definitions.sql.SQLImplicitFKJoin;
import no.fellesstudentsystem.graphql.mapping.GenerationDirective;
import no.fellesstudentsystem.graphql.mapping.GraphQLDirectiveParam;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static no.fellesstudentsystem.graphql.mapping.GenerationDirective.COLUMN;
import static no.fellesstudentsystem.graphql.mapping.GenerationDirective.REFERENCE;
import static no.fellesstudentsystem.graphql.mapping.GraphQLDirectiveParam.*;
import static no.fellesstudentsystem.graphql.schema.SchemaHelpers.*;

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
            var columnValue = getDirectiveArgumentString(field, COLUMN, COLUMN.getParamName(NAME));
            unprocessedNameInput = fieldType.isID() ? columnValue.toLowerCase() : columnValue;
            upperCaseName = columnValue.toUpperCase();
        } else {
            unprocessedNameInput = name;
            upperCaseName = name.toUpperCase();
        }

        fieldReferences = new ArrayList<>();

        if (!field.getDirectives(REFERENCE.getName()).isEmpty()) {
            var refrenceDirective = field.getDirectives(REFERENCE.getName()).get(0);

            Optional.ofNullable(refrenceDirective.getArgument(VIA.getName()))
                    .map(Argument::getValue)
                    .ifPresent(this::addViaFieldReferences);

            if (refrenceDirective.getArguments().stream()
                    .map(Argument::getName)
                    .anyMatch(it -> it.equals(TABLE.getName()) || it.equals(GraphQLDirectiveParam.CONDITION.getName()) || it.equals(KEY.getName()))) {
                fieldReferences.add(new FieldReference(field));
            } else if (fieldType != null && !fieldReferencesContainsReferredFieldType(fieldType)) {
                fieldReferences.add(new FieldReference(new JOOQTableMapping(fieldType.getName()), "", null));
            }
        }

        if (field.hasDirective(GenerationDirective.CONDITION.getName()) && fieldType != null) {
            condition = new SQLCondition(
                    getDirectiveArgumentString(field, GenerationDirective.CONDITION, GenerationDirective.CONDITION.getParamName(GraphQLDirectiveParam.NAME)),
                    getOptionalDirectiveArgumentBoolean(field, GenerationDirective.CONDITION, GenerationDirective.CONDITION.getParamName(OVERRIDE)).orElse(false)
            );
        } else {
            condition = null;
        }
        this.fieldType = fieldType;
        mappingFromFieldName = new MethodMapping(name);
        mappingFromColumn = new MethodMapping(unprocessedNameInput);
    }

    private void addViaFieldReferences(Value viaValue) {
        var values = viaValue instanceof ArrayValue ? ((ArrayValue) viaValue).getValues() : List.of(((ObjectValue) viaValue));

        values.stream()
                .filter(value -> value instanceof ObjectValue)
                .forEach(value -> {
                    List<ObjectField> objectFields = ((ObjectValue) value).getObjectFields();
                    Optional<ObjectField> table = getObjectFieldByName(objectFields, TABLE.getName());
                    Optional<ObjectField> key = getObjectFieldByName(objectFields, KEY.getName());
                    Optional<ObjectField> condition = getObjectFieldByName(objectFields, GraphQLDirectiveParam.CONDITION.getName());

                    fieldReferences.add(
                            new FieldReference(
                                    new JOOQTableMapping(table.map(AbstractField::stringValueOf).orElse("")),
                                    key.map(AbstractField::stringValueOf).orElse(""),
                                    condition.map(ii -> new SQLCondition(stringValueOf(ii))).orElse(null))
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

    @Nullable
    protected <T extends NamedNode<T> & DirectivesContainer<T>> SQLImplicitFKJoin getSqlRoleJoin(T field) {
        return getOptionalDirectiveArgumentString(field, COLUMN, COLUMN.getParamName(TABLE))
                .map(table -> new SQLImplicitFKJoin(
                        table,
                        getOptionalDirectiveArgumentString(field, COLUMN, COLUMN.getParamName(KEY)).orElse(""))
                )
                .orElse(null);
    }

    private static String stringValueOf(ObjectField objectField) {
        return ((StringValue) objectField.getValue()).getValue();
    }

    private static Optional<ObjectField> getObjectFieldByName(List<ObjectField> objectFields, String name) {
        return objectFields.stream()
                .filter(objectField -> objectField.getName().equals(name))
                .findFirst();
    }

    private boolean fieldReferencesContainsReferredFieldType(FieldType referringFieldType) {
        return fieldReferences.stream()
                .map(FieldReference::getTable)
                .map(JOOQTableMapping::getName)
                .anyMatch(tableName -> tableName.equalsIgnoreCase(referringFieldType.getName()));
    }
}
