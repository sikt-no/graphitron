package no.sikt.graphitron.generators.codebuilding;

import com.squareup.javapoet.ParameterizedTypeName;
import com.squareup.javapoet.TypeName;

import static no.sikt.graphitron.mappings.JavaPoetClassName.*;

/**
 * Helper methods for handling javapoet TypeNames.
 */
public class ClassNameFormat {
    private static final ParameterizedTypeName stringSetClassName = ParameterizedTypeName.get(SET.className, STRING.className);

    /**
     * @return The type wrapped in a List ParameterizedTypeName, if the boolean condition is true.
     */
    public static TypeName wrapListIf(TypeName type, boolean condition) {
        return condition ? wrapList(type) : type;
    }

    /**
     * @return The type wrapped in a Set ParameterizedTypeName, if the boolean condition is true.
     */
    public static TypeName wrapSetIf(TypeName type, boolean condition) {
        return condition ? wrapSet(type) : type;
    }

    /**
     * @return The type wrapped in a Map ParameterizedTypeName with String as key, if the boolean condition is true.
     */
    public static TypeName wrapStringMapIf(TypeName type, boolean condition) {
        return condition ? wrapStringMap(type) : type;
    }

    /**
     * @return The type wrapped in a List ParameterizedTypeName.
     */
    public static ParameterizedTypeName wrapList(TypeName type) {
        return ParameterizedTypeName.get(LIST.className, type);
    }

    /**
     * @return The type wrapped in a Set ParameterizedTypeName.
     */
    public static ParameterizedTypeName wrapSet(TypeName type) {
        return ParameterizedTypeName.get(SET.className, type);
    }

    /**
     * @return The type wrapped in a CompletableFuture ParameterizedTypeName.
     */
    public static ParameterizedTypeName wrapFuture(TypeName type) {
        return ParameterizedTypeName.get(COMPLETABLE_FUTURE.className, type);
    }

    /**
     * @return The type wrapped in a Map ParameterizedTypeName with String as key.
     */
    public static ParameterizedTypeName wrapStringMap(TypeName type) {
        return ParameterizedTypeName.get(MAP.className, STRING.className, type);
    }

    /**
     * @return The type wrapped in a Map ParameterizedTypeName with key.
     */
    public static ParameterizedTypeName wrapMap(TypeName key, TypeName type) {
        return ParameterizedTypeName.get(MAP.className, key, type);
    }

    /**
     * @return The ParameterizedTypeName for a Set of Strings.
     */
    public static ParameterizedTypeName getStringSetTypeName() {
        return stringSetClassName;
    }
}
