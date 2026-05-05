package no.sikt.graphitron.rewrite;

import graphql.schema.GraphQLEnumType;
import graphql.schema.GraphQLInputObjectField;
import graphql.schema.GraphQLInputObjectType;
import graphql.schema.GraphQLInputType;
import graphql.schema.GraphQLList;
import graphql.schema.GraphQLNamedType;
import graphql.schema.GraphQLNonNull;
import graphql.schema.GraphQLScalarType;
import graphql.schema.GraphQLType;
import no.sikt.graphitron.rewrite.selection.GraphQLSelectionParseException;
import no.sikt.graphitron.rewrite.selection.GraphQLSelectionParser;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
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

    /**
     * Result of the {@link #of} factory.
     *
     * <p>{@link UnknownArgRef} fires when the head segment of an override doesn't name a slot at
     * the directive's scope. {@link PathRejected} fires when a tail segment fails structural
     * validation against the GraphQL schema (walks through scalar/enum/union/interface, names a
     * field that doesn't exist on the input-object at that depth). The two arms are distinct so
     * the caller's switch can render them with the appropriate "head segment" vs. "path tail"
     * site context.
     */
    sealed interface Result {
        record Ok(ArgBindingMap map) implements Result {}
        record UnknownArgRef(String message) implements Result {}
        record PathRejected(String message) implements Result {}
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
     * Builds a binding map from {@code slotTypes} (the GraphQL slots in scope at the directive
     * site, mapped to their input types) and {@code overrides} (parsed segment chains, keyed by
     * Java target). Returns:
     *
     * <ul>
     *   <li>{@link Result.UnknownArgRef} when any override's head segment is not a key in
     *       {@code slotTypes}. The message names the unknown source and the available list. Site
     *       context (which directive the override sits on) is added by the caller.</li>
     *   <li>{@link Result.PathRejected} when a tail segment fails structural validation against
     *       the input-object type at that depth: walking through a scalar/enum/union/interface,
     *       or a field name that does not exist on the input-object at that depth. The message
     *       names the offending segment and (when applicable) suggests a close match.</li>
     *   <li>{@link Result.Ok} with the resolved {@link PathExpr} chain for every override and
     *       identity {@link PathExpr.Head} entries for every unclaimed slot.</li>
     * </ul>
     *
     * <p>For each {@code Step} in a resolved {@link PathExpr}, {@code liftsList} is set to
     * {@code true} when the GraphQL field's type at that depth is list-shaped (after stripping
     * non-null wrappers). Walks through nested non-null/list wrappers transparently so that
     * walking from a {@code [B]!} field's element-type B into B's child fields succeeds.
     *
     * <p>{@code overrides} is trusted to have unique keys (the parser enforces it).
     */
    static Result of(Map<String, GraphQLInputType> slotTypes, Map<String, List<String>> overrides) {
        var resolvedOverrides = new LinkedHashMap<String, PathExpr>();
        var claimedSlots = new LinkedHashSet<String>();
        for (var entry : overrides.entrySet()) {
            String javaTarget = entry.getKey();
            List<String> segments = entry.getValue();
            if (segments.isEmpty()) {
                continue; // parser guarantees non-empty; defensive only
            }
            String head = segments.get(0);
            if (!slotTypes.containsKey(head)) {
                return new Result.UnknownArgRef(
                    "argMapping entry '" + javaTarget + ": " + String.join(".", segments)
                    + "' references GraphQL argument '" + head
                    + "', but available arguments are " + formatNameSet(slotTypes.keySet()));
            }
            claimedSlots.add(head);
            PathExpr expr = PathExpr.head(head);
            GraphQLInputType currentFieldType = slotTypes.get(head);
            for (int i = 1; i < segments.size(); i++) {
                String segName = segments.get(i);
                String dottedPath = String.join(".", segments);
                GraphQLType walkType = unwrapForTraversal(currentFieldType);
                if (!(walkType instanceof GraphQLInputObjectType inputObj)) {
                    return new Result.PathRejected(
                        "argMapping entry '" + javaTarget + ": " + dottedPath
                        + "' walks through " + describeKind(walkType) + " at segment '"
                        + segments.get(i - 1) + "'; only input-object types may be traversed");
                }
                GraphQLInputObjectField nextField = inputObj.getField(segName);
                if (nextField == null) {
                    var candidates = inputObj.getFields().stream()
                        .map(GraphQLInputObjectField::getName)
                        .toList();
                    return new Result.PathRejected(
                        "argMapping entry '" + javaTarget + ": " + dottedPath
                        + "': segment '" + segName + "' does not exist on input type '"
                        + inputObj.getName() + "'"
                        + BuildContext.candidateHint(segName, candidates));
                }
                GraphQLInputType fieldType = nextField.getType();
                boolean liftsList = isListShaped(fieldType);
                expr = PathExpr.step(expr, segName, liftsList);
                currentFieldType = fieldType;
            }
            resolvedOverrides.put(javaTarget, expr);
        }
        // Identity for every GraphQL arg whose slot is not claimed by an override; then overrides
        // on top. Skipping claimed slots removes the would-be identity entry whose key would
        // otherwise be a stale Java target (e.g. argMapping "inputs: input" against slot "input"
        // means the Java param is "inputs", not "input"). Two overrides binding to the same slot
        // is legal: argMapping "a: x, b: x" against slot {x} yields {a: x, b: x}.
        var byJavaName = new LinkedHashMap<String, PathExpr>();
        for (String slot : slotTypes.keySet()) {
            if (!claimedSlots.contains(slot)) {
                byJavaName.put(slot, PathExpr.head(slot));
            }
        }
        byJavaName.putAll(resolvedOverrides);
        return new Result.Ok(new ArgBindingMap(Collections.unmodifiableMap(byJavaName)));
    }

    /**
     * Strips non-null and list wrappers in any order to expose the innermost named type for path
     * traversal. {@code [B]!} → {@code B}, {@code [[B]!]!} → {@code B}, etc.
     */
    private static GraphQLType unwrapForTraversal(GraphQLType t) {
        var current = t;
        while (current instanceof GraphQLNonNull nn) {
            current = nn.getWrappedType();
        }
        while (current instanceof GraphQLList list) {
            current = list.getWrappedType();
            while (current instanceof GraphQLNonNull nn) {
                current = nn.getWrappedType();
            }
        }
        return current;
    }

    /** True when {@code t} (after stripping a single layer of non-null) is a list. */
    private static boolean isListShaped(GraphQLInputType t) {
        GraphQLType current = t;
        while (current instanceof GraphQLNonNull nn) {
            current = nn.getWrappedType();
        }
        return current instanceof GraphQLList;
    }

    /** Human-readable description of a non-input-object type for {@link Result.PathRejected}. */
    private static String describeKind(GraphQLType t) {
        if (t instanceof GraphQLScalarType s) return "scalar '" + s.getName() + "'";
        if (t instanceof GraphQLEnumType e) return "enum '" + e.getName() + "'";
        if (t instanceof GraphQLNamedType n) return n.getClass().getSimpleName().replace("GraphQL", "").toLowerCase()
            + " '" + n.getName() + "'";
        return t.toString();
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
