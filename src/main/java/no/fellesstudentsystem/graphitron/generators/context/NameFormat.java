package no.fellesstudentsystem.graphitron.generators.context;

import no.fellesstudentsystem.graphitron.generators.abstractions.DBClassGenerator;
import org.apache.commons.lang3.StringUtils;
import org.jetbrains.annotations.NotNull;

import java.util.stream.Collectors;
import java.util.stream.Stream;

import static no.fellesstudentsystem.graphql.naming.GraphQLReservedName.NODE_TYPE;
import static org.apache.commons.lang3.StringUtils.*;

/**
 * Helper methods for formatting names.
 */
public class NameFormat {
    public static final String
            VARIABLE_COUNT_PREFIX = "count",
            VARIABLE_GET_PREFIX = "get",
            VARIABLE_RESULT_SUFFIX = "Result",
            VARIABLE_LIST_SUFFIX = "List",
            VARIABLE_ITERATE_PREFIX = "it",
            RECORD_NAME_SUFFIX = "Record";

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
    public static String asGetMethodName(String source, String field) {
        return VARIABLE_GET_PREFIX + capitalize(source) + capitalize(field);
    }

    /**
     * @return Inputs formatted as a get call.
     */
    @NotNull
    public static String asGetMethodName(String field) {
        return VARIABLE_GET_PREFIX + capitalize(field);
    }

    /**
     * @return Field type formatted as a query method call.
     */
    @NotNull
    public static String asQueryClass(String s) {
        return capitalize(s) + DBClassGenerator.FILE_NAME_SUFFIX;
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
        return Stream
                .of(name.toLowerCase().split("_(?![0-9_]+)"))
                .map(StringUtils::capitalize)
                .collect(Collectors.joining(""));
    }
}
