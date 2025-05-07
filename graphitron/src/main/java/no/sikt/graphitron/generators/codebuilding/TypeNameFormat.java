package no.sikt.graphitron.generators.codebuilding;

import no.sikt.graphitron.javapoet.ClassName;
import no.sikt.graphitron.javapoet.ParameterizedTypeName;
import no.sikt.graphitron.javapoet.TypeName;
import no.sikt.graphitron.configuration.GeneratorConfig;

import static no.sikt.graphitron.mappings.JavaPoetClassName.*;

/**
 * Helper methods for handling javapoet TypeNames.
 */
public class TypeNameFormat {
    private static final ParameterizedTypeName
            STRING_SET = ParameterizedTypeName.get(SET.className, STRING.className),
            STRING_OBJECT_MAP = ParameterizedTypeName.get(MAP.className, STRING.className, OBJECT.className);

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
     * @return The type wrapped in a DataFetcher ParameterizedTypeName.
     */
    public static ParameterizedTypeName wrapFetcher(TypeName type) {
        return ParameterizedTypeName.get(DATA_FETCHER.className, type);
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
        return STRING_SET;
    }

    /**
     * @return The ParameterizedTypeName for a Map of Objects by Strings.
     */
    public static ParameterizedTypeName getObjectMapTypeName() {
        return STRING_OBJECT_MAP;
    }

    /**
     * @param subPath Path from the top of the output package.
     * @param name Name of the class.
     * @return ClassName based on the default output package and the provided subpath.
     */
    public static ClassName getGeneratedClassName(String subPath, String name) {
        return ClassName.get(GeneratorConfig.outputPackage() + "." + subPath, name);
    }
}
