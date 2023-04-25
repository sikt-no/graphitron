package no.fellesstudentsystem.graphitron.definitions.mapping;

import static org.apache.commons.lang3.StringUtils.capitalize;

/**
 * Stores operations related to rendering input method calls.
 */
public class MethodMapping {
    private final String name, get, getCall, set, setCallPart;

    public MethodMapping(String name) {
        this.name = name;
        get = "get" + capitalize(name);
        getCall = "." + get + "()";

        set = "set" + capitalize(name);
        setCallPart = "." + set + "(";
    }

    /**
     * @return The exact name of the data that this object corresponds to.
     */
    public String getName() {
        return name;
    }

    /**
     * @return The name for a get(name) method call.
     */
    public String asGet() {
        return get;
    }

    /**
     * @return Format this name as a .get(name)() method call.
     */
    public String asGetCall() {
        return getCall;
    }

    /**
     * @return The name for a set(name) method call.
     */
    public String asSet() {
        return set;
    }

    /**
     * @return Format this column name for a .set(name)() method call.
     */
    public String asSetCall(String input) {
        return setCallPart + input + ")";
    }
}
