package no.sikt.graphitron.definitions.fields.containedtypes;

import graphql.language.ListType;
import graphql.language.NonNullType;
import graphql.language.Type;
import graphql.language.TypeName;
import no.sikt.graphitron.definitions.helpers.ScalarUtils;

import java.util.ArrayList;

/**
 * Class that contains all the necessary information about a field's type and nullability.
 */
public class FieldType extends no.sikt.graphql.schema.FieldType {
    private String name;
    private com.palantir.javapoet.TypeName typeClass;
    private boolean isNonNullable = false;
    private boolean isIterableNonNullable = false;
    private boolean isIterableWrapped = false;
    private boolean isID = false;

    public FieldType(Type<?> fieldType) {
        super(fieldType);
        setFields(fieldType);
    }

    protected void setFields(Type<?> fieldType) {
        ArrayList<Type<?>> orderedTypeList = extractNestedTypes(fieldType);
        boolean isThisNonNullable = false;
        for (Type<?> objectType : orderedTypeList) {
            if (objectType instanceof NonNullType) {
                isThisNonNullable = true;
            }
            else {
                if (objectType instanceof ListType) {
                    isIterableWrapped = true;
                    isIterableNonNullable = isThisNonNullable;
                }
                else if (objectType instanceof TypeName) {
                    name = ((TypeName) objectType).getName();
                    typeClass = ScalarUtils.getInstance().getScalarTypeMapping(name);
                    isNonNullable = isThisNonNullable;
                    isID = name.equals("ID");
                }

                isThisNonNullable = false;
            }
        }
    }

    /**
     * @return The name of the data type as specified in the schema.
     */
    public String getName() {
        return name;
    }

    /**
     * @return Is this data type wrapped in a list?
     */
    public boolean isIterableWrapped() {
        return isIterableWrapped;
    }

    public boolean isNullable() {
        return !isNonNullable;
    }

    /**
     * @return Is this data type's list wrapping nullable?
     */
    public boolean isIterableNullable() {
        return !isIterableNonNullable;
    }

    /**
     * @return Is this data type an ID type?
     */
    public boolean isID() {
        return isID;
    }

    /**
     * @return {@link com.palantir.javapoet.TypeName} for this field.
     */
    public com.palantir.javapoet.TypeName getTypeClass() {
        return typeClass;
    }
}
