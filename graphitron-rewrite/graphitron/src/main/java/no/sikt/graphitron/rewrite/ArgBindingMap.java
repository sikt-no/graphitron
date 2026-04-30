package no.sikt.graphitron.rewrite;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Pre-resolved binding map used by {@link ServiceCatalog#reflectServiceMethod} and
 * {@link ServiceCatalog#reflectTableMethod} to bind reflected method parameters to their
 * GraphQL counterparts.
 *
 * <p>Keys are Java parameter names; values are the GraphQL argument (or input-field) names that
 * should bind to them. Identity entries ({@code key.equals(value)}) cover the no-override case;
 * override entries ({@code key != value}) name a Java parameter that differs from the GraphQL
 * argument's own name (R53: {@code argMapping} on an {@code ExternalCodeReference}).
 *
 * <p>The single {@link #of} factory is axis-agnostic. It builds identity entries for every name
 * in {@code graphqlArgNames} and then applies {@code overrides} on top — replacing identity
 * entries whose Java target collides with an override key, so a Java parameter named {@code X}
 * stops binding to GraphQL arg {@code X} once an override claims it. The only failure shape is
 * {@link Result.UnknownArgRef}: an override whose GraphQL-source value is not in
 * {@code graphqlArgNames}. The parser ({@link #parseArgMapping}) enforces unique Java targets,
 * so the {@code overrides} map cannot have duplicate keys; identity entries cannot collide with
 * each other (GraphQL arg names are unique on a field), so {@code of(...)} has no collision
 * shape.
 *
 * <p>The post-reflection typo guard inside {@link ServiceCatalog} only fires for explicit
 * override entries ({@code key != value}); identity entries fall through to the existing
 * per-parameter mismatch error.
 */
record ArgBindingMap(Map<String, String> byJavaName) {

    /** Result of the {@link #of} factory. */
    sealed interface Result {
        record Ok(ArgBindingMap map) implements Result {}
        record UnknownArgRef(String message) implements Result {}
    }

    /** Result of {@link #parseArgMapping}. */
    sealed interface ParsedArgMapping {
        record Ok(Map<String, String> overrides) implements ParsedArgMapping {}
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
        var byJavaName = new LinkedHashMap<String, String>();
        var claimedSlots = new java.util.HashSet<>(overrides.values());
        for (String argName : graphqlArgNames) {
            if (!claimedSlots.contains(argName)) {
                byJavaName.put(argName, argName);
            }
        }
        byJavaName.putAll(overrides);
        return new Result.Ok(new ArgBindingMap(Map.copyOf(byJavaName)));
    }

    /**
     * Parses {@code raw} as a comma-separated list of {@code javaParam: graphqlArg} entries.
     * Whitespace (including newlines for text-block input) is permitted around {@code :} and
     * {@code ,}. Empty/null/blank input returns an {@link ParsedArgMapping.Ok} with an empty
     * map (identity-for-every-parameter).
     *
     * <p>Returns {@link ParsedArgMapping.ParseError} on a malformed entry (missing {@code :},
     * empty key or value after trimming) or a duplicate Java target across entries.
     */
    static ParsedArgMapping parseArgMapping(String raw) {
        if (raw == null || raw.isBlank()) {
            return new ParsedArgMapping.Ok(Map.of());
        }
        var entries = raw.split(",");
        var overrides = new LinkedHashMap<String, String>();
        for (var rawEntry : entries) {
            var entry = rawEntry.strip();
            if (entry.isEmpty()) {
                return new ParsedArgMapping.ParseError(
                    "argMapping has an empty entry — entries are comma-separated 'javaParam: graphqlArg' pairs");
            }
            int colon = entry.indexOf(':');
            if (colon < 0) {
                return new ParsedArgMapping.ParseError(
                    "argMapping entry '" + entry + "' is missing ':' — expected 'javaParam: graphqlArg'");
            }
            String javaParam = entry.substring(0, colon).strip();
            String graphqlArg = entry.substring(colon + 1).strip();
            if (javaParam.isEmpty()) {
                return new ParsedArgMapping.ParseError(
                    "argMapping entry '" + entry + "' has an empty Java-parameter name before ':'");
            }
            if (graphqlArg.isEmpty()) {
                return new ParsedArgMapping.ParseError(
                    "argMapping entry '" + entry + "' has an empty GraphQL-argument name after ':'");
            }
            String prior = overrides.put(javaParam, graphqlArg);
            if (prior != null) {
                return new ParsedArgMapping.ParseError(
                    "argMapping has duplicate entries for Java parameter '" + javaParam
                    + "' — each Java parameter may appear at most once");
            }
        }
        return new ParsedArgMapping.Ok(Map.copyOf(overrides));
    }

    private static String formatNameSet(Set<String> names) {
        if (names.isEmpty()) return "[]";
        return names.stream().sorted().collect(java.util.stream.Collectors.joining("', '", "['", "']"));
    }
}
