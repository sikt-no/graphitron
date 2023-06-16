package no.fellesstudentsystem.graphitron.definitions.mapping;

import org.apache.commons.lang3.StringUtils;

import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.apache.commons.lang3.StringUtils.capitalize;

/**
 * Stores operations related to rendering table method calls.
 */
public class JOOQTableMapping {
    private final String name, codeName, getId, getIdCall, hasId, hasIdCallPart, hasIds, hasIdsCallPart;

    public JOOQTableMapping(String name) {
        this.name = name.toUpperCase();
        var codeNameUpper = Stream
                .of(name.toLowerCase().split("_"))
                .map(StringUtils::capitalize)
                .collect(Collectors.joining(""));
        codeName = StringUtils.uncapitalize(codeNameUpper);
        getId = "get" + codeNameUpper + "Id";
        getIdCall = "." + getId + "()";
        hasId = "has" + capitalize(codeName) + "Id";
        hasIdCallPart = "." + hasId + "(";
        hasIds = hasId + "s";
        hasIdsCallPart = "." + hasIds + "(";
    }

    /**
     * @return The exact name of the data that this object corresponds to.
     */
    public String getName() {
        return name;
    }

    /**
     * @return The camelcase code version of the column name.
     */
    public String getCodeName() {
        return codeName;
    }
}
