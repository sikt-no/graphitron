package no.fellesstudentsystem.graphitron.generators.context;

import com.squareup.javapoet.ParameterizedTypeName;
import com.squareup.javapoet.TypeName;

import static no.fellesstudentsystem.graphitron.mappings.JavaPoetClassName.*;

public class ClassNameFormat {
    private static final TypeName stringSetClassName = ParameterizedTypeName.get(SET.className, STRING.className);

    public static TypeName wrapListIf(TypeName type, boolean condition) {
        return condition ? wrapList(type) : type;
    }

    public static TypeName wrapSetIf(TypeName type, boolean condition) {
        return condition ? wrapSet(type) : type;
    }

    public static TypeName wrapStringMapIf(TypeName type, boolean condition) {
        return condition ? wrapStringMap(type) : type;
    }

    public static TypeName wrapList(TypeName type) {
        return ParameterizedTypeName.get(LIST.className, type);
    }

    public static TypeName wrapSet(TypeName type) {
        return ParameterizedTypeName.get(SET.className, type);
    }

    public static TypeName wrapFuture(TypeName type) {
        return ParameterizedTypeName.get(COMPLETABLE_FUTURE.className, type);
    }

    public static TypeName wrapStringMap(TypeName type) {
        return ParameterizedTypeName.get(MAP.className, STRING.className, type);
    }

    public static TypeName getStringSetTypeName() {
        return stringSetClassName;
    }
}
