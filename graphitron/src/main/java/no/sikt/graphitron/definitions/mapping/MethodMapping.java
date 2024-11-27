package no.sikt.graphitron.definitions.mapping;

import com.squareup.javapoet.CodeBlock;

import static no.sikt.graphitron.generators.codebuilding.NameFormat.toCamelCase;
import static no.sikt.graphitron.mappings.JavaPoetClassName.COLLECTORS;
import static org.apache.commons.lang3.StringUtils.capitalize;

/**
 * Stores operations related to rendering get and set method calls.
 */
public class MethodMapping {
    private final String name, get, set, setCallPart, hasCallPart, hasIterableCallPart;
    private final CodeBlock getCall;

    public MethodMapping(String name) {
        this.name = name;
        get = "get" + capitalize(name);
        getCall = CodeBlock.of(".$L()", get);

        set = "set" + capitalize(name);
        setCallPart = "." + set + "(";

        var has = "has" + capitalize(toCamelCase(name));
        var hasIterable = has + "s";
        hasCallPart = "." + has + "(";
        hasIterableCallPart = "." + hasIterable + "(";
    }

    /**
     * @return The exact name of the data that this object corresponds to.
     */
    public String getName() {
        return name;
    }

    /**
     * @return The name for a get[name] method call.
     */
    public String asGet() {
        return get;
    }

    /**
     * @return Format this name as a .get[name]() method call.
     */
    public CodeBlock asGetCall() {
        return getCall;
    }

    /**
     * @return The name for a set[name] method call.
     */
    public String asSet() {
        return set;
    }

    /**
     * @return Format this name for a .set[name]([input]) method call.
     */
    public CodeBlock asSetCall(String input) {
        return CodeBlock.builder().addStatement("$L$N)", setCallPart, input).build();
    }

    /**
     * @return Format this name for a .set[name]([input]) method call.
     */
    public CodeBlock asSetCall(CodeBlock input) {
        return CodeBlock.builder().addStatement("$L$L)", setCallPart, input).build();
    }

    /**
     * @return Format this name as a .has[name][s]([input]) method call.
     */
    public CodeBlock asHasCall(CodeBlock input, boolean iterable) {
        if (iterable) {
            return CodeBlock.of("$L$L)", hasIterableCallPart, CodeBlock.of("$L.stream().collect($T.toSet())", input, COLLECTORS.className));
        }
        return CodeBlock.of("$L$L)", hasCallPart, input);
    }
}
