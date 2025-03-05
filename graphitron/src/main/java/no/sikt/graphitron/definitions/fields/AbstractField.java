package no.sikt.graphitron.definitions.fields;

import com.palantir.javapoet.TypeName;
import graphql.language.DirectivesContainer;
import graphql.language.NamedNode;
import no.sikt.graphitron.definitions.fields.containedtypes.FieldType;
import no.sikt.graphitron.definitions.interfaces.FieldSpecification;
import no.sikt.graphitron.definitions.mapping.MethodMapping;

import static no.sikt.graphql.directives.DirectiveHelpers.getDirectiveArgumentString;
import static no.sikt.graphql.directives.DirectiveHelpers.getOptionalDirectiveArgumentString;
import static no.sikt.graphql.directives.GenerationDirective.FIELD;
import static no.sikt.graphql.directives.GenerationDirectiveParam.JAVA_NAME;
import static no.sikt.graphql.directives.GenerationDirectiveParam.NAME;
import static no.sikt.graphql.naming.GraphQLReservedName.SCHEMA_MUTATION;
import static no.sikt.graphql.naming.GraphQLReservedName.SCHEMA_QUERY;

/**
 * This class represents the general functionality associated with GraphQLs object fields.
 */
public abstract class AbstractField<T extends NamedNode<T> & DirectivesContainer<T>> implements FieldSpecification {
    private final FieldType fieldType;
    private final String name, javaName, upperCaseName, unprocessedFieldOverrideInput, containerType;
    private final MethodMapping mappingFromSchemaName, mappingFromFieldOverride;
    private final boolean hasSetFieldOverride;

    public AbstractField(T field, String container) {
        this(field ,null, container);
    }

    public AbstractField(T field, FieldType fieldType, String container) {
        name = field.getName();
        containerType = container;
        hasSetFieldOverride = field.hasDirective(FIELD.getName());
        if (hasSetFieldOverride) {
            var columnValue = getDirectiveArgumentString(field, FIELD, NAME);
            unprocessedFieldOverrideInput = columnValue;
            upperCaseName = columnValue.toUpperCase();
            var javaColumnValue = getOptionalDirectiveArgumentString(field, FIELD, JAVA_NAME);
            javaName = javaColumnValue.orElse("");
        } else {
            unprocessedFieldOverrideInput = name;
            upperCaseName = name.toUpperCase();
            javaName = "";
        }

        this.fieldType = fieldType;
        mappingFromSchemaName = new MethodMapping(name);
        mappingFromFieldOverride = new MethodMapping(unprocessedFieldOverrideInput);
    }

    @Override
    public boolean hasSetFieldOverride() {
        return hasSetFieldOverride;
    }

    @Override
    public String getTypeName() {
        return fieldType.getName();
    }

    @Override
    public String getContainerTypeName() {
        return containerType;
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

    @Override
    public TypeName getTypeClass() {
        return fieldType.getTypeClass();
    }

    @Override
    public String getName() {
        return name;
    }

    public String getJavaName() {
        return javaName;
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
        return containerType.equals(SCHEMA_QUERY.getName()) || containerType.equals(SCHEMA_MUTATION.getName());
    }
}
