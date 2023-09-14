package no.fellesstudentsystem.graphitron.mappings;

import java.lang.reflect.InvocationTargetException;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static no.fellesstudentsystem.graphitron.generators.context.NameFormat.toCamelCase;
import static no.fellesstudentsystem.graphitron.mappings.TableReflection.*;
import static org.apache.commons.lang3.StringUtils.capitalize;

public class PersonHack {
    private final static String
            FIELD_METHOD_PREFIX = "get",
            FIELD_METHOD_SUFFIX = "FieldNames",
            PERSONLOPENR = "PERSONLOPENR",
            PERSONNR = "PERSONNR",
            FODSELSDATO = "FODSELSDATO";

    /**
     * FS HACK for PERSONLOPENR.
     * @return The same list of fieldNames, but each PERSONLOPENR is replaced by PERSONNR and FODSELSDATO.
     */
    public static List<String> asHackedIDFields(Collection<String> fieldNames) {
        return fieldNames.stream().map(String::toUpperCase).flatMap(PersonHack::convert).collect(Collectors.toList());
    }

    /**
     * FS-specific HACK for PERSONLOPENR.
     * @return The fields for this ID, where PERSONLOPENR is replaced by PERSONNR and FODSELSDATO.
     */
    public static Optional<List<String>> getHackedIDFields(String tableName, String idName) {
        return getIDFields(tableName, toCamelCase(idName)).map(names -> names.stream().map(String::toUpperCase).flatMap(PersonHack::convert).collect(Collectors.toList()));
    }

    public static Optional<List<String>> getIDFields(String tableName, String idName) {
        var methodName = FIELD_METHOD_PREFIX + capitalize(idName) + FIELD_METHOD_SUFFIX;
        var field = getTablesField(tableName);
        if (field.isPresent() && tableHasMethod(tableName, methodName)) {
            var fieldMethod = Stream
                    .of(field.get().getType().getMethods())
                    .filter(it -> it.getName().equals(methodName))
                    .findFirst()
                    .orElseThrow();
            try {
                var fields = fieldMethod.invoke(null);
                if (fields instanceof List) {
                    return Optional.of((List<String>) fields);
                }
            } catch (IllegalAccessException | InvocationTargetException e) {
                throw new RuntimeException(e);
            }
        }
        return Optional.empty();
    }

    private static Stream<String> convert(String fieldName) {
        if (fieldName.startsWith(PERSONLOPENR)) {
            var postfix = fieldName.substring(PERSONLOPENR.length());
            return Stream.of(PERSONNR + postfix, FODSELSDATO + postfix);
        }

        return Stream.of(fieldName);
    }
}
