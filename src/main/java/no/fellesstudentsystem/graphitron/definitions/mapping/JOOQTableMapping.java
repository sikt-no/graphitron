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

    /**
     * @return The name as a get(table)Id method call.
     */
    public String asGetId() {
        return getId;
    }

    /**
     * @return Format this table name as a .get(table)Id() method call.
     */
    public String asGetIdCall() {
        return getIdCall;
    }

    /**
     * @return The name as a has(table)Id method call.
     */
    public String asHasId() {
        return hasId;
    }

    /**
     * @return The name as a has(table)Ids method call.
     */
    public String asHasIds() {
        return hasIds;
    }

    /**
     * @return Format this table name as a .has(table)Id() method call.
     */
    public String asHasIdCall(String input) {
        return hasIdCallPart + input + ")";
    }

    /**
     * @return Format this table name as a .has(table)Ids() method call.
     */
    public String asHasIdsCall(String input) {
        return hasIdsCallPart + input + ")";
    }
}
