package no.sikt.graphitron.definitions.mapping;

import no.sikt.graphitron.javapoet.CodeBlock;

import static no.sikt.graphitron.generators.codebuilding.NameFormat.RESOLVER_KEY_DTO_SUFFIX;
import static no.sikt.graphitron.generators.codebuilding.NameFormat.toCamelCase;
import static no.sikt.graphitron.mappings.JavaPoetClassName.COLLECTORS;
import static org.apache.commons.lang3.StringUtils.capitalize;

/**
 * Stores operations related to rendering get and set method calls.
 */
public class MethodMapping {
    private final String name, get, camelGet, set, has, camelHas, setCallPart, setKeyCallPart, hasCallPart, hasIterableCallPart, camelHasCallPart, camelHasIterableCallPart;
    private final CodeBlock getCall;

    public MethodMapping(String name) {
        this.name = name;
        get = "get" + capitalize(name);
        getCall = CodeBlock.of(".$L()", get);
        camelGet = "get" + capitalize(toCamelCase(name));

        set = "set" + capitalize(name);
        setCallPart = "." + set + "(";
        setKeyCallPart = "." + set + RESOLVER_KEY_DTO_SUFFIX + "(";

        has = "has" + capitalize(name);
        var hasIterable = has + "s";
        hasCallPart = "." + has + "(";
        hasIterableCallPart = "." + hasIterable + "(";

        camelHas = "has" + capitalize(toCamelCase(name));
        var camelHasIterable = camelHas + "s";
        camelHasCallPart = "." + camelHas + "(";
        camelHasIterableCallPart = "." + camelHasIterable + "(";
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
     * @return The name for a get[name] method call.
     */
    public String asCamelGet() {
        return camelGet;
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
        return CodeBlock.statementOf("$L$N)", setCallPart, input);
    }

    /**
     * @return Format this name for a .set[name]([input]) method call.
     */
    public CodeBlock asSetCall(CodeBlock input) {
        return CodeBlock.statementOf("$L$L)", setCallPart, input);
    }

    public CodeBlock asSetKeyCall(CodeBlock input) {
        return CodeBlock.builder().addStatement("$L$L)", setKeyCallPart, input).build();
    }

    /**
     * @return The name for a has[name] method call.
     */
    public String asHas() {
        return has;
    }

    /**
     * @return The name for a has[name] method call.
     */
    public String asCamelHas() {
        return camelHas;
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

    /**
     * @return Format this name as a .has[name][s]([input]) method call.
     */
    public CodeBlock asCamelHasCall(CodeBlock input, boolean iterable) {
        if (iterable) {
            return CodeBlock.of("$L$L)", camelHasIterableCallPart, CodeBlock.of("$L.stream().collect($T.toSet())", input, COLLECTORS.className));
        }
        return CodeBlock.of("$L$L)", camelHasCallPart, input);
    }
}
