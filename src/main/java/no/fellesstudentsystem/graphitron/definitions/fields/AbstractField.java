package no.fellesstudentsystem.graphitron.definitions.fields;

import graphql.language.*;
import no.fellesstudentsystem.graphitron.definitions.mapping.MethodMapping;
import no.fellesstudentsystem.graphitron.definitions.sql.SQLCondition;
import no.fellesstudentsystem.graphitron.definitions.sql.SQLImplicitFKJoin;
import no.fellesstudentsystem.graphql.mapping.GraphQLDirectiveParam;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static no.fellesstudentsystem.graphql.mapping.GenerationDirective.*;
import static no.fellesstudentsystem.graphql.mapping.GenerationDirective.CONDITION;
import static no.fellesstudentsystem.graphql.mapping.GraphQLDirectiveParam.*;
import static no.fellesstudentsystem.graphql.mapping.GraphQLDirectiveParam.TABLE;
import static no.fellesstudentsystem.graphql.schema.SchemaHelpers.*;

/**
 * This class represents the general functionality associated with GraphQLs object fields.
 */
public abstract class AbstractField {
    private final FieldType fieldType;
    private final String name, upperCaseName, unprocessedNameInput;
    private final FieldReference fieldReference;
    private final SQLCondition condition;
    private final MethodMapping mappingFromFieldName, mappingFromColumn;

    private <T extends NamedNode<T> & DirectivesContainer<T>> AbstractField(T field, FieldType f) {
        name = field.getName();
        if (field.hasDirective(COLUMN.getName())) {
            unprocessedNameInput = getDirectiveArgumentString(field, COLUMN, COLUMN.getParamName(NAME));
            upperCaseName = unprocessedNameInput.toUpperCase();
        } else {
            unprocessedNameInput = name;
            upperCaseName = name.toUpperCase();
        }
        fieldReference = field.hasDirective(REFERENCE.getName()) && f != null ? new FieldReference(field) : null;
        if (field.hasDirective(CONDITION.getName()) && f != null) {
            condition = new SQLCondition(
                    getDirectiveArgumentString(field, CONDITION, CONDITION.getParamName(GraphQLDirectiveParam.NAME)),
                    getOptionalDirectiveArgumentBoolean(field, CONDITION, CONDITION.getParamName(OVERRIDE)).orElse(false)
            );
        } else {
            condition = null;
        }
        fieldType = f;
        mappingFromFieldName = new MethodMapping(name);
        mappingFromColumn = new MethodMapping(unprocessedNameInput);
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

    public FieldReference getFieldReference() {
        return fieldReference;
    }

    public boolean hasFieldReference() {
        return fieldReference != null;
    }

    public Optional<String> getFieldReferenceTableIfPresent() {
        if (hasFieldReference() && fieldReference.hasTable()) {
            return Optional.of(fieldReference.getTable().getName());
        }
        return Optional.empty();
    }

    public SQLCondition getCondition() {
        return condition;
    }

    public String applyCondition(List<String> inputs) {
        return condition.formatToString(inputs);
    }

    public String applyCondition(List<String> inputs, Map<String, Method> conditionOverrides) {
        return condition.formatToString(inputs, conditionOverrides);
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
}
