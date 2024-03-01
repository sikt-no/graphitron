package no.fellesstudentsystem.graphitron.definitions.fields;

import graphql.language.DirectivesContainer;
import graphql.language.NamedNode;
import no.fellesstudentsystem.graphitron.definitions.interfaces.FieldSpecification;
import no.fellesstudentsystem.graphitron.definitions.mapping.MethodMapping;

import static no.fellesstudentsystem.graphql.directives.DirectiveHelpers.getDirectiveArgumentString;
import static no.fellesstudentsystem.graphql.directives.GenerationDirective.FIELD;
import static no.fellesstudentsystem.graphql.directives.GenerationDirective.NOT_GENERATED;
import static no.fellesstudentsystem.graphql.directives.GenerationDirectiveParam.NAME;

/**
 * This class represents the general functionality associated with GraphQLs object fields.
 */
public abstract class AbstractField<T extends NamedNode<T> & DirectivesContainer<T>> implements FieldSpecification {
    private final FieldType fieldType;
    private final String name, upperCaseName, unprocessedFieldOverrideInput;
    private final MethodMapping mappingFromSchemaName, mappingFromFieldOverride;
    private final boolean explicitlyNotGenerated, hasSetFieldOverride;

    public AbstractField(T field) {
        this(field ,null);
    }

    public AbstractField(T field, FieldType fieldType) {
        name = field.getName();
        hasSetFieldOverride = field.hasDirective(FIELD.getName());
        if (hasSetFieldOverride) {
            var columnValue = getDirectiveArgumentString(field, FIELD, NAME);
            unprocessedFieldOverrideInput = columnValue;
            upperCaseName = columnValue.toUpperCase();
        } else {
            unprocessedFieldOverrideInput = name;
            upperCaseName = name.toUpperCase();
        }

        this.fieldType = fieldType;
        mappingFromSchemaName = new MethodMapping(name);
        mappingFromFieldOverride = new MethodMapping(unprocessedFieldOverrideInput);
        explicitlyNotGenerated = field.hasDirective(NOT_GENERATED.getName());
    }

    @Override
    public boolean hasSetFieldOverride() {
        return hasSetFieldOverride;
    }

    public boolean isExplicitlyNotGenerated() {
        return explicitlyNotGenerated;
    }

    @Override
    public String getTypeName() {
        return fieldType.getName();
    }

    /**
     * @return Is this field an ID?
     */
    @Override
    public boolean isID() {
        return fieldType.isID();
    }

    @Override
    public boolean isIterableWrapped() {
        return fieldType.isIterableWrapped();
    }

    @Override
    public boolean isNullable() {
        return fieldType.isIterableWrapped() ? fieldType.isIterableNullable() : fieldType.isNullable();
    }

    @Override
    public boolean isNonNullable() {
        return fieldType.isIterableWrapped() ? !fieldType.isIterableNullable() : !fieldType.isNullable();
    }

    /**
     * @return {@link com.squareup.javapoet.TypeName} for this field's type.
     */
    public com.squareup.javapoet.TypeName getTypeClass() {
        return fieldType.getTypeClass();
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public String getUpperCaseName() {
        return upperCaseName;
    }

    public String getUnprocessedFieldOverrideInput() {
        return unprocessedFieldOverrideInput;
    }

    @Override
    public MethodMapping getMappingFromSchemaName() {
        return mappingFromSchemaName;
    }

    @Override
    public MethodMapping getMappingFromFieldOverride() {
        return mappingFromFieldOverride;
    }

    /**
     * @return Is this object field a Query or Mutation root field?
     */
    public boolean isRootField() {
        return false;
    }
}
