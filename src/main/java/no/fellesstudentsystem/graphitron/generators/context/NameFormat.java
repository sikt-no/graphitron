package no.fellesstudentsystem.graphitron.generators.context;

import no.fellesstudentsystem.graphitron.definitions.fields.ObjectField;
import no.fellesstudentsystem.graphitron.definitions.objects.ObjectDefinition;
import no.fellesstudentsystem.graphitron.generators.abstractions.DBClassGenerator;
import org.jetbrains.annotations.NotNull;

import static no.fellesstudentsystem.graphql.mapping.GraphQLReservedName.NODE_TYPE;
import static org.apache.commons.lang3.StringUtils.capitalize;
import static org.apache.commons.lang3.StringUtils.uncapitalize;

public class NameFormat {
    private static final String
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
    public static String asGetMethodVariableName(String fieldSourceTypeName, String fieldName) {
        return uncapitalize(fieldSourceTypeName) + capitalize(fieldName);
    }

    @NotNull
    public static String asCountMethodName(ObjectField referenceField, ObjectDefinition objectDefinition) {
        return VARIABLE_COUNT_PREFIX  + capitalize(asQueryMethodName(referenceField, objectDefinition));
    }

    @NotNull
    public static String asQueryMethodName(ObjectField referenceField, ObjectDefinition objectDefinition) {
        return referenceField.getName() + "For" + objectDefinition.getName();
    }

    /**
     * @return Inputs formatted as a get call.
     */
    @NotNull
    public static String asGetMethodName(String fieldSourceTypeName, String fieldName) {
        return VARIABLE_GET_PREFIX + fieldSourceTypeName + capitalize(fieldName);
    }

    /**
     * @return Field type formatted as a query method call.
     */
    @NotNull
    public static String asQueryClass(String fieldType) {
        return fieldType + DBClassGenerator.FILE_NAME_SUFFIX;
    }

    /**
     * @return Field type formatted as a node interface method call.
     */
    @NotNull
    public static String asQueryNodeMethod(String fieldType) {
        return "load" + fieldType + "ByIdsAs" + NODE_TYPE.getName();
    }

    /**
     * @return Input formatted as a list version of itself.
     */
    @NotNull
    public static String asListedName(String s) {
        return uncapitalize(s) + VARIABLE_LIST_SUFFIX;
    }

    /**
     * @return Input formatted as a result name.
     */
    @NotNull
    public static String asResultName(String s) {
        return uncapitalize(s) + VARIABLE_RESULT_SUFFIX;
    }

    /**
     * @return Input formatted as an iterable name.
     */
    @NotNull
    public static String asIterable(String s) {
        return VARIABLE_ITERATE_PREFIX + capitalize(s);
    }

    /**
     * @return Input formatted as an iterable result name.
     */
    @NotNull
    public static String asIterableResultName(String s) {
        return asIterable(asResultName(s));
    }

    /**
     * @return Format this string as a record naming pattern.
     */
    @NotNull
    public static String asRecordName(String name) {
        return name + RECORD_NAME_SUFFIX;
    }
}
