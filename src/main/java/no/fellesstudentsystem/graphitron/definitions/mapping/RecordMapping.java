package no.fellesstudentsystem.graphitron.definitions.mapping;

import org.apache.commons.lang3.StringUtils;

import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Stores operations related to rendering record method calls.
 */
public class RecordMapping {
    private final String name, codeName, set, setCallPart;

    public RecordMapping(String name) {
        var codeNameUpper = Stream
                .of(name.toLowerCase().split("_"))
                .map(StringUtils::capitalize)
                .collect(Collectors.joining(""));
        this.name = codeNameUpper;
        codeName = StringUtils.uncapitalize(codeNameUpper);

        set = "set" + codeNameUpper;
        setCallPart = "." + set + "(";
    }

    /**
     * @return The exact name of the data that this object corresponds to.
     */
    public String getName() {
        return name;
    }

    /**
     * @return The camelcase code version of the name.
     */
    public String getCodeName() {
        return codeName;
    }

    /**
     * @return The name for a set(column) method call.
     */
    public String asSet() {
        return set;
    }

    /**
     * @return Format this column name for a .set(column)() method call.
     */
    public String asSetCall(String input) {
        return setCallPart + input + ")";
    }
}
