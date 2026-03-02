package no.sikt.graphitron.generators.codebuilding;

import no.sikt.graphitron.generators.db.DBClassGenerator;
import no.sikt.graphitron.generators.mapping.JavaRecordMapperClassGenerator;
import no.sikt.graphitron.generators.mapping.RecordMapperClassGenerator;
import org.apache.commons.lang3.StringUtils;
import org.jetbrains.annotations.NotNull;

import java.util.stream.Collectors;
import java.util.stream.Stream;

import static no.sikt.graphitron.generators.codebuilding.VariablePrefix.namedIteratorPrefix;
import static no.sikt.graphql.naming.GraphQLReservedName.FEDERATION_ENTITY_UNION;
import static no.sikt.graphql.naming.GraphQLReservedName.NODE_TYPE;
import static org.apache.commons.lang3.StringUtils.capitalize;
import static org.apache.commons.lang3.StringUtils.uncapitalize;

/**
 * Helper methods for formatting names.
 */
public class NameFormat {
    public static final String
            PREFIX_COUNT = "count",
            PREFIX_GET = "get",
            SUFFIX_LIST = "List",
            PREFIX_VALIDATE = "validate",
            SUFFIX_RECORD = "Record",
            SUFFIX_RECORD_TRANSFORM = "ToJOOQ" + SUFFIX_RECORD,
            SUFFIX_RECORD_TRANSFORM_JAVA = "ToJava" + SUFFIX_RECORD,
            SUFFIX_RESPONSE_TRANSFORM = "ToGraphType",
            SUFFIX_RESPONSE_TRANSFORM_JOOQ = SUFFIX_RECORD + SUFFIX_RESPONSE_TRANSFORM,
            SUFFIX_RESPONSE_TRANSFORM_JAVA = SUFFIX_RESPONSE_TRANSFORM,
            SUFFIX_TYPE_RESOLVER = "TypeResolver",
            SUFFIX_RESOLVER_KEY_DTO = "Key";

    /**
     * @return Inputs formatted as a get call, but without the get element of the string.
     */
    @NotNull
    public static String asGetMethodVariableName(String source, String fieldName) {
        return uncapitalize(source) + capitalize(fieldName);
    }

    /**
     * @return This field and containing type formatted as a counting query method name.
     */
    @NotNull
    public static String asCountMethodName(String field, String container) {
        return PREFIX_COUNT + capitalize(asQueryMethodName(field, container));
    }

    /**
     * @return This field and containing type formatted as a fetch query method name.
     */
    @NotNull
    public static String asQueryMethodName(String field, String container) {
        return uncapitalize(field) + "For" + capitalize(container);
    }

    /**
     * @return This field and containing type formatted as an entity fetch query method name.
     */
    @NotNull
    public static String asEntityQueryMethodName(String prefix) {
        return uncapitalize(prefix) + "AsEntity";
    }

    /**
     * @return Format this name as a method name for a type resolver.
     */
    @NotNull
    public static String asTypeResolverMethodName(String target) {
        return uncapitalize(target.replace("_", "")) + SUFFIX_TYPE_RESOLVER;
    }

    /**
     * @return Inputs formatted as a get call.
     */
    @NotNull
    public static String asGetMethodName(String field, String type) {
        return PREFIX_GET + capitalize(field) + capitalize(type);
    }

    /**
     * @return Inputs formatted as a get call.
     */
    @NotNull
    public static String asGetMethodName(String field) {
        return PREFIX_GET + capitalize(field);
    }

    /**
     * @return Name formatted as a query class name.
     */
    @NotNull
    public static String asQueryClass(String s) {
        return capitalize(s) + DBClassGenerator.FILE_NAME_SUFFIX;
    }

    /**
     * @return Name formatted as a record mapper class name.
     */
    @NotNull
    public static String asRecordMapperClass(String s, boolean isJavaRecord, boolean isInput) {
        if (isInput) {
            return capitalize(s) + (isJavaRecord ? JavaRecordMapperClassGenerator.FILE_NAME_TO_SUFFIX : RecordMapperClassGenerator.FILE_NAME_TO_SUFFIX);
        }
        return capitalize(s) + (isJavaRecord ? JavaRecordMapperClassGenerator.FILE_NAME_FROM_SUFFIX : RecordMapperClassGenerator.FILE_NAME_FROM_SUFFIX);
    }

    /**
     * @return Interface method naming for this interface implementation.
     */
    public static String interfaceOrUnionQueryName(String implementationName, String interfaceName) {
        return String.format("%sFor%s", uncapitalize(implementationName), capitalize(interfaceName));
    }

    /**
     * @return Field type formatted as a node interface method call.
     */
    @NotNull
    public static String asNodeQueryName(String s) {
        return interfaceOrUnionQueryName(s, NODE_TYPE.getName());
    }

    public static String asEntitiesQueryName(String s) {
        return interfaceOrUnionQueryName(s, FEDERATION_ENTITY_UNION.getName());
    }

    /**
     * @return Input formatted as a list version of itself.
     */
    @NotNull
    public static String asListedName(String s) {
        return uncapitalize(s) + SUFFIX_LIST;
    }

    /**
     * @return Input formatted as a list version of itself, if condition is true.
     */
    @NotNull
    public static String asListedNameIf(String s, boolean condition) {
        return condition ? uncapitalize(s) + SUFFIX_LIST : uncapitalize(s);
    }

    /**
     * @return Format this string as a record naming pattern.
     */
    @NotNull
    public static String asRecordName(String s) {
        return uncapitalize(s) + SUFFIX_RECORD;
    }

    /**
     * @return Format this string as a record transform method naming pattern.
     */
    @NotNull
    public static String recordTransformMethod(String s, boolean isJavaRecord, boolean isInput) {
        if (isInput) {
            return uncapitalize(s) + (isJavaRecord ? SUFFIX_RECORD_TRANSFORM_JAVA : SUFFIX_RECORD_TRANSFORM);
        }
        return uncapitalize(s) + (isJavaRecord ? SUFFIX_RESPONSE_TRANSFORM_JAVA : SUFFIX_RESPONSE_TRANSFORM_JOOQ);
    }

    /**
     * @return Format a record transform method naming pattern.
     */
    @NotNull
    public static String recordTransformMethod(boolean isJavaRecord, boolean isInput) {
        if (isInput) {
            return uncapitalize(isJavaRecord ? SUFFIX_RECORD_TRANSFORM_JAVA : SUFFIX_RECORD_TRANSFORM);
        }
        return uncapitalize(isJavaRecord ? SUFFIX_RESPONSE_TRANSFORM_JAVA : SUFFIX_RESPONSE_TRANSFORM_JOOQ);
    }

    /**
     * @return Format a record validation method naming pattern.
     */
    @NotNull
    public static String recordValidateMethod() {
        return PREFIX_VALIDATE;
    }

    /**
     * @return Input formatted as an iterable name, if condition is true.
     */
    @NotNull
    public static String namedIteratorPrefixIf(String s, boolean condition) {
        return condition ? namedIteratorPrefix(s) : uncapitalize(s);
    }

    /**
     * @return Format this string as an iterable record naming pattern.
     */
    @NotNull
    public static String asListedRecordName(String name) {
        return asListedName(asRecordName(name));
    }

    /**
     * @return Format this string as a record naming pattern. It is iterable if condition is set to true
     */
    @NotNull
    public static String asListedRecordNameIf(String name, boolean condition) {
        return asListedNameIf(asRecordName(name), condition);
    }

    /**
     * Camel case conversion with special handling for numbers.
     */
    public static String toCamelCase(String name) {
        return uncapitalize(
                Stream
                        .of(name.toLowerCase().split("_(?![0-9_]+)"))
                        .map(StringUtils::capitalize)
                        .collect(Collectors.joining())
        );
    }
}
