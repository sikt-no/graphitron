package no.sikt.graphitron.definitions.fields;

import no.sikt.graphitron.javapoet.TypeName;
import graphql.language.OperationTypeDefinition;
import no.sikt.graphitron.definitions.fields.containedtypes.FieldType;
import no.sikt.graphitron.definitions.interfaces.FieldSpecification;
import no.sikt.graphitron.definitions.mapping.MethodMapping;

/**
 * This class represents operation fields in the schema type.
 */
public class OperationField implements FieldSpecification {
    private final FieldType fieldType;
    private final String name;

    public OperationField(OperationTypeDefinition field) {
        name = field.getName();
        fieldType = new FieldType(field.getTypeName());
    }

    @Override
    public boolean hasSetFieldOverride() {
        return false;
    }

    @Override
    public String getTypeName() {
        return fieldType.getName();
    }

    @Override
    public String getContainerTypeName() {
        return null;
    }

    @Override
    public boolean isID() {
        return fieldType.isID();
    }

    public boolean hasNodeID() {
        return false;
    }

    public String getNodeIdTypeName() {
        return null;
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

    @Override
    public String getUpperCaseName() {
        return name;
    }

    @Override
    public MethodMapping getMappingFromSchemaName() {
        return null;
    }

    @Override
    public MethodMapping getMappingFromFieldOverride() {
        return null;
    }

    @Override
    public boolean isRootField() {
        return true;
    }
}
