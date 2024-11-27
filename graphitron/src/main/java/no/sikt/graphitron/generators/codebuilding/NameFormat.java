package no.sikt.graphitron.generators.codebuilding;

import no.sikt.graphitron.generators.abstractions.DBClassGenerator;
import no.sikt.graphitron.generators.resolvers.mapping.JavaRecordMapperClassGenerator;
import no.sikt.graphitron.generators.resolvers.mapping.RecordMapperClassGenerator;
import org.apache.commons.lang3.StringUtils;
import org.jetbrains.annotations.NotNull;

import java.util.stream.Collectors;
import java.util.stream.Stream;

import static no.sikt.graphql.naming.GraphQLReservedName.NODE_TYPE;
import static org.apache.commons.lang3.StringUtils.capitalize;
import static org.apache.commons.lang3.StringUtils.uncapitalize;

/**
 * Helper methods for formatting names.
 */
public class NameFormat {
    public static final String
            VARIABLE_COUNT_PREFIX = "count",
            VARIABLE_GET_PREFIX = "get",
            VARIABLE_RESULT_SUFFIX = "",
            VARIABLE_LIST_SUFFIX = "List",
            VARIABLE_ITERATE_PREFIX = "it",
            RECORD_NAME_SUFFIX = "Record",
            INDEX_NAME_SUFFIX = "Index",
            RECORD_TRANSFORM_SUFFIX = "ToJOOQ" + RECORD_NAME_SUFFIX,
            RECORD_TRANSFORM_JAVA_SUFFIX = "ToJava" + RECORD_NAME_SUFFIX,
            RESPONSE_TRANSFORM_SUFFIX = "ToGraphType",
            RESPONSE_TRANSFORM_JOOQ_SUFFIX = RECORD_NAME_SUFFIX + RESPONSE_TRANSFORM_SUFFIX,
            RESPONSE_TRANSFORM_JAVA_SUFFIX = RESPONSE_TRANSFORM_SUFFIX,
            VALIDATE_PREFIX = "validate";

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
        return VARIABLE_COUNT_PREFIX + capitalize(asQueryMethodName(field, container));
    }

    /**
     * @return This field and containing type formatted as a fetch query method name.
     */
    @NotNull
    public static String asQueryMethodName(String field, String container) {
        return uncapitalize(field) + "For" + capitalize(container);
    }

    /**
     * @return Inputs formatted as a get call.
     */
    @NotNull
    public static String asGetMethodName(String field, String type) {
        return VARIABLE_GET_PREFIX + capitalize(field) + capitalize(type);
    }

    /**
     * @return Inputs formatted as a get call.
     */
    @NotNull
    public static String asGetMethodName(String field) {
        return VARIABLE_GET_PREFIX + capitalize(field);
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
     * @return Field type formatted as a node interface method call.
     */
    @NotNull
    public static String asQueryNodeMethod(String s) {
        return "load" + capitalize(s) + "ByIdsAs" + NODE_TYPE.getName();
    }

    /**
     * @return Input formatted as a list version of itself.
     */
    @NotNull
    public static String asListedName(String s) {
        return uncapitalize(s) + VARIABLE_LIST_SUFFIX;
    }

    /**
     * @return Input formatted as a list version of itself, if condition is true.
     */
    @NotNull
    public static String asListedNameIf(String s, boolean condition) {
        return condition ? uncapitalize(s) + VARIABLE_LIST_SUFFIX : uncapitalize(s);
    }

    /**
     * @return Input formatted as a result name.
     */
    @NotNull
    public static String asResultName(String s) {
        return uncapitalize(s) + VARIABLE_RESULT_SUFFIX;
    }

    /**
     * @return Format this string as a record naming pattern.
     */
    @NotNull
    public static String asRecordName(String s) {
        return uncapitalize(s) + RECORD_NAME_SUFFIX;
    }

    /**
     * @return Format this string as a record class naming pattern.
     */
    @NotNull
    public static String asRecordClassName(String s) {
        return capitalize(s) + RECORD_NAME_SUFFIX;
    }

    /**
     * @return Format this string as an index naming pattern.
     */
    @NotNull
    public static String asIndexName(String s) {
        return uncapitalize(s) + INDEX_NAME_SUFFIX;
    }

    /**
     * @return Format this string as a record transform method naming pattern.
     */
    @NotNull
    public static String recordTransformMethod(String s, boolean isJavaRecord, boolean isInput) {
        if (isInput) {
            return uncapitalize(s) + (isJavaRecord ? RECORD_TRANSFORM_JAVA_SUFFIX : RECORD_TRANSFORM_SUFFIX);
        }
        return uncapitalize(s) + (isJavaRecord ? RESPONSE_TRANSFORM_JAVA_SUFFIX : RESPONSE_TRANSFORM_JOOQ_SUFFIX);
    }

    /**
     * @return Format a record transform method naming pattern.
     */
    @NotNull
    public static String recordTransformMethod(boolean isJavaRecord, boolean isInput) {
        if (isInput) {
            return uncapitalize(isJavaRecord ? RECORD_TRANSFORM_JAVA_SUFFIX : RECORD_TRANSFORM_SUFFIX);
        }
        return uncapitalize(isJavaRecord ? RESPONSE_TRANSFORM_JAVA_SUFFIX : RESPONSE_TRANSFORM_JOOQ_SUFFIX);
    }

    /**
     * @return Format a record validation method naming pattern.
     */
    @NotNull
    public static String recordValidateMethod() {
        return VALIDATE_PREFIX;
    }

    /**
     * @return Input formatted as an iterable name.
     */
    @NotNull
    public static String asIterable(String s) {
        return VARIABLE_ITERATE_PREFIX + capitalize(s);
    }

    /**
     * @return Input formatted as an iterable name, if condition is true.
     */
    @NotNull
    public static String asIterableIf(String s, boolean condition) {
        return condition ? VARIABLE_ITERATE_PREFIX + capitalize(s) : uncapitalize(s);
    }

    /**
     * @return Input formatted as an iterable result name.
     */
    @NotNull
    public static String asIterableResultName(String s) {
        return asIterable(asResultName(s));
    }

    /**
     * @return Input formatted as a result name. It is iterable if condition is set to true.
     */
    @NotNull
    public static String asIterableResultNameIf(String s, boolean condition) {
        return asIterableIf(asResultName(s), condition);
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
