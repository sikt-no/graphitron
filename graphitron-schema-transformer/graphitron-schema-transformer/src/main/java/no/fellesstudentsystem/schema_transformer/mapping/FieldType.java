package no.fellesstudentsystem.schema_transformer.mapping;

import graphql.language.Type;
import graphql.language.TypeName;

import java.util.ArrayList;

/**
 * Class that extracts information about a field's type.
 */
public class FieldType {
    private String name;
    public FieldType(Type<?> fieldType) {
        ArrayList<Type<?>> orderedTypeList = extractNestedTypes(fieldType);
        for (Type<?> objectType : orderedTypeList) {
            if (objectType instanceof TypeName) {
                name = ((TypeName) objectType).getName();
            }
        }
    }

    private ArrayList<Type<?>> extractNestedTypes(Type<?> t) {
        var typeList = new ArrayList<Type<?>>();
        typeList.add(t);
        while(!(t instanceof TypeName)) {
            var subType = (Type<?>) t.getNamedChildren().getChildOrNull("type");
            typeList.add(subType);
            t = subType;
        }
        return typeList;
    }

    /**
     * @return The name of the data type as specified in the schema.
     */
    public String getName() {
        return name;
    }
}
