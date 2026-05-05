package no.sikt.graphitron.rewrite;

import no.sikt.graphitron.rewrite.selection.GraphQLSelectionParseException;
import no.sikt.graphitron.rewrite.selection.GraphQLSelectionParser;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Pre-resolved binding map used by {@link ServiceCatalog#reflectServiceMethod} and
 * {@link ServiceCatalog#reflectTableMethod} to bind reflected method parameters to their
 * GraphQL counterparts.
 *
 * <p>Keys are Java parameter names; values are {@link PathExpr} expressions that resolve to a
 * GraphQL slot (and, post-R84, optionally a path into a nested input field). Identity entries
 * ({@code key.equals(value.headName()) && value.isHead()}) cover the no-override case; override
 * entries name a Java parameter that differs from the GraphQL argument's own name, optionally
 * walking into a nested input field via dot-segments.
 *
 * <p>The single {@link #of} factory is axis-agnostic. It builds identity {@link PathExpr.Head}
 * entries for every name in {@code graphqlArgNames} and then applies {@code overrides} on top
 * — replacing identity entries whose Java target collides with an override key, so a Java
 * parameter named {@code X} stops binding to GraphQL arg {@code X} once an override claims it.
 * The only failure shape on the head axis is {@link Result.UnknownArgRef}: an override whose
 * head-segment value is not in {@code graphqlArgNames}. The parser ({@link #parseArgMapping})
 * enforces unique Java targets, so the {@code overrides} map cannot have duplicate keys; identity
 * entries cannot collide with each other (GraphQL arg names are unique on a field), so
 * {@code of(...)} has no collision shape.
 *
 * <p>The post-reflection typo guard inside {@link ServiceCatalog} only fires for explicit
 * override entries (where the Java target differs from the head-segment name); identity entries
 * fall through to the existing per-parameter mismatch error.
 */
record ArgBindingMap(Map<String, PathExpr> byJavaName) {

    /** Result of the {@link #of} factory. */
    sealed interface Result {
        record Ok(ArgBindingMap map) implements Result {}
        record UnknownArgRef(String message) implements Result {}
    }

    /**
     * Result of {@link #parseArgMapping}.
     *
     * <p>{@code overrides} keys are Java parameter names; values are dot-segment chains.
     * R53-shaped single-name overrides arrive as one-element segment lists; R84 path
     * expressions arrive as multi-element lists with the head segment first. Single-segment
     * overrides preserve full R53 wire-compat at every consumer.
     */
    sealed interface ParsedArgMapping {
        record Ok(Map<String, List<String>> overrides) implements ParsedArgMapping {}
        record ParseError(String message) implements ParsedArgMapping {}
    }

    private static final ArgBindingMap EMPTY = new ArgBindingMap(Map.of());

    /** No bindings — used by path-step {@code @condition} resolution where the method takes no args. */
    static ArgBindingMap empty() {
        return EMPTY;
    }

    /**
     * Builds a binding map from {@code graphqlArgNames} (the GraphQL slots in scope at the
     * directive site) and {@code overrides} (parsed from {@code argMapping}, keyed by Java
     * target). Returns {@link Result.UnknownArgRef} when any override's GraphQL-source value is
     * not in {@code graphqlArgNames}; the message names the unknown source and the available
     * list. Site context (which directive the override sits on) is added by the caller.
     *
     * <p>{@code overrides} is trusted to have unique keys (the parser enforces it).
     */
    static Result of(Set<String> graphqlArgNames, Map<String, String> overrides) {
        for (var entry : overrides.entrySet()) {
            if (!graphqlArgNames.contains(entry.getValue())) {
                return new Result.UnknownArgRef(
                    "argMapping entry '" + entry.getKey() + ": " + entry.getValue()
                    + "' references GraphQL argument '" + entry.getValue()
                    + "', but available arguments are " + formatNameSet(graphqlArgNames));
            }
        }
        // Identity for every GraphQL arg whose slot is not claimed by an override; then overrides
        // on top. Skipping claimed slots removes the would-be identity entry whose key would
        // otherwise be a stale Java target (e.g. argMapping "inputs: input" against slot "input"
        // means the Java param is "inputs", not "input"). Two overrides binding to the same slot
        // is legal: argMapping "a: x, b: x" against slot {x} yields {a: x, b: x}.
        var byJavaName = new LinkedHashMap<String, PathExpr>();
        var claimedSlots = new java.util.HashSet<>(overrides.values());
        for (String argName : graphqlArgNames) {
            if (!claimedSlots.contains(argName)) {
                byJavaName.put(argName, PathExpr.head(argName));
            }
        }
        for (var entry : overrides.entrySet()) {
            byJavaName.put(entry.getKey(), PathExpr.head(entry.getValue()));
        }
        return new Result.Ok(new ArgBindingMap(Collections.unmodifiableMap(byJavaName)));
    }

    /**
     * Parses {@code raw} as a comma-separated list of {@code javaParam: dotted.path} entries.
     * Whitespace (including newlines for text-block input) and commas are insignificant
     * between entries (standard GraphQL convention; the lexer in {@link GraphQLSelectionParser}
     * already handles this). Empty/null/blank input returns an {@link ParsedArgMapping.Ok}
     * with an empty map (identity-for-every-parameter).
     *
     * <p>Single-name R53-shaped overrides (e.g. {@code "inputs: input"}) parse to a one-element
     * segment chain {@code ["input"]}; path expressions (e.g.
     * {@code "kvotesporsmal: input.kvotesporsmalId"}) parse to a multi-element chain
     * {@code ["input", "kvotesporsmalId"]}.
     *
     * <p>Returns {@link ParsedArgMapping.ParseError} on a syntactic problem surfaced by
     * {@link GraphQLSelectionParser#parseEntries(String)} (missing colon, missing value name,
     * empty path segment) or a duplicate Java target across entries.
     */
    static ParsedArgMapping parseArgMapping(String raw) {
        List<no.sikt.graphitron.rewrite.selection.ParsedEntry> entries;
        try {
            entries = GraphQLSelectionParser.parseEntries(raw);
        } catch (GraphQLSelectionParseException e) {
            return new ParsedArgMapping.ParseError(
                "argMapping syntax error — " + e.getMessage()
                + " (expected comma-separated 'javaParam: graphqlArg' or 'javaParam: input.field' pairs)");
        }
        var overrides = new LinkedHashMap<String, List<String>>();
        for (var entry : entries) {
            if (overrides.containsKey(entry.key())) {
                return new ParsedArgMapping.ParseError(
                    "argMapping has duplicate entries for Java parameter '" + entry.key()
                    + "' — each Java parameter may appear at most once");
            }
            overrides.put(entry.key(), entry.segments());
        }
        return new ParsedArgMapping.Ok(Collections.unmodifiableMap(overrides));
    }

    private static String formatNameSet(Set<String> names) {
        if (names.isEmpty()) return "[]";
        return names.stream().sorted().collect(java.util.stream.Collectors.joining("', '", "['", "']"));
    }
}
