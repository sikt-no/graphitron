package no.sikt.graphitron.rewrite;

import graphql.schema.GraphQLInputObjectField;
import graphql.schema.GraphQLInputObjectType;
import graphql.schema.GraphQLInputType;
import graphql.schema.GraphQLList;
import graphql.schema.GraphQLNonNull;
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
 * Pre-resolved binding map used by {@link ServiceCatalog#reduceClaims} and
 * {@link ServiceCatalog#reflectTableMethod} to bind reflected method parameters to their
 * GraphQL counterparts.
 *
 * <p>Keys are Java parameter names; values are {@link PathExpr} expressions that resolve to a
 * GraphQL slot, optionally a path into a nested input field. Identity entries
 * ({@code key.equals(value.headName()) && value.isHead()}) cover the no-override case; override
 * entries name a Java parameter that differs from the GraphQL argument's own name, optionally
 * walking into a nested input field via dot-segments.
 *
 * <p>The post-reflection typo guard inside {@link ServiceCatalog} only fires for explicit
 * override entries (where the Java target differs from the head-segment name); identity entries
 * fall through to the per-parameter mismatch error.
 */
record ArgBindingMap(Map<String, PathExpr> byJavaName) {

    /**
     * Result of the {@link #of} factory: the one seam every directive's {@code argMapping}
     * resolution passes through.
     *
     * <p>{@link UnknownArgRef} fires when the head segment of an override doesn't name a slot at
     * the directive's scope. {@link PathRejected} fires when a tail segment fails structural
     * validation against the GraphQL schema. They stay distinct records rather than collapsing
     * into one string because a site may qualify one arm and not the other:
     * {@code BuildContext.resolveConditionRef} resolves against an empty slot map, so its
     * "available arguments are []" rendering only makes sense with the extra
     * "no GraphQL arguments are in scope at a path-step {@code @condition}" clause it adds to
     * the {@link UnknownArgRef} arm alone.
     *
     * <p>{@link Failure} is the shared read for the majority of sites, which lift both arms into
     * the same failure channel with the same site prefix. Matching it instead of the two arms
     * keeps the wording in one place per site; a site that needs the distinction still matches
     * the records.
     */
    sealed interface Result {
        record Ok(ArgBindingMap map) implements Result {}

        /**
         * The two failure arms of {@link #of}, sharing the message accessor so a site that treats
         * them identically writes one arm. Site context (which directive the override sits on) is
         * added by the caller.
         */
        sealed interface Failure extends Result {
            String message();
        }

        record UnknownArgRef(String message) implements Failure {}
        record PathRejected(String message) implements Failure {}
    }

    /**
     * Result of {@link #parseArgMapping}.
     *
     * <p>{@code overrides} keys are Java parameter names; values are dot-segment chains.
     * Single-name overrides arrive as one-element segment lists; dot-path expressions into
     * nested input fields arrive as multi-element lists with the head segment first.
     *
     * <p>{@code sigilBindings} are the recognized sigil entries lifted out by
     * {@link ArgMappingSigil#scan} before tokenization (Java parameter name to sigil literal);
     * empty for every site that admits none and for the sigil-unaware single-arg overload.
     */
    sealed interface ParsedArgMapping {
        record Ok(Map<String, List<String>> overrides, Map<String, String> sigilBindings)
                implements ParsedArgMapping {
            Ok(Map<String, List<String>> overrides) {
                this(overrides, Map.of());
            }
        }
        record ParseError(String message) implements ParsedArgMapping {}
    }

    private static final ArgBindingMap EMPTY = new ArgBindingMap(Map.of());

    /** No bindings; used by path-step {@code @condition} resolution where the method takes no args. */
    static ArgBindingMap empty() {
        return EMPTY;
    }

    /**
     * Builds a binding map from {@code slotTypes} (the GraphQL slots in scope at the directive
     * site, mapped to their input types) and {@code overrides} (parsed segment chains, keyed by
     * Java target): {@link Result.Ok} carries the resolved {@link PathExpr} chain for every
     * override plus identity {@link PathExpr.Head} entries for every unclaimed slot; the failure
     * arms are described on {@link Result}. Site context (which directive the override sits on)
     * is added by the caller.
     *
     * <p>For each {@code Step} in a resolved {@link PathExpr}, {@code liftsList} is set to
     * {@code true} when the GraphQL field's type at that depth is list-shaped (after stripping
     * non-null wrappers). Walks through nested non-null/list wrappers transparently so that
     * walking from a {@code [B]!} field's element-type B into B's child fields succeeds.
     *
     * <p>{@code overrides} is trusted to have unique keys (the parser enforces it).
     *
     * <h4>Segments below an input object</h4>
     *
     * <p>A path may descend past something that is not an input object, which is how a key column of
     * a {@code @nodeId}'s node type gets named. The remaining segments are carried as ordinary
     * {@link PathExpr.Step}s and that is the whole of this method's contribution: they are
     * <em>carried, never interpreted</em>.
     *
     * <p>No judgment about them lives here, and the reason is not convenience. Whether the thing
     * being opened carries a {@code @nodeId}, whether it names a type, whether the trailing segment
     * is one of that type's key columns, whether there is exactly one of them, and whether the
     * column's Java type fits the parameter are all resolutions over captured facts, answered by
     * {@code intent_argmapping_binding_leaf} and the relations that reduce it. This method cannot
     * consult any of them: the schema is built before capture runs, so the store is empty while this
     * executes. A rule spelled here would therefore be a second, earlier, unfalsifiable copy of an
     * answer the store already gives, and the copy would win by rejecting first.
     *
     * <p>What keeps a carried segment safe is the pipeline order. Capture and validation both run
     * before planning and rendering, so a path the store cannot resolve fails the build before any
     * emitter sees it. The safety of carrying is exactly the completeness of those detections, which
     * is where a reader should go to audit it.
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
                    // Below an input object there is no SDL surface left to resolve against, so the
                    // rest of the path is carried verbatim and judged by the store. Every remaining
                    // segment, not one: how many there are is what the leaf relation's
                    // trailing_segments counts, and truncating here would hide a miscount from it.
                    for (int j = i; j < segments.size(); j++) {
                        expr = PathExpr.step(expr, segments.get(j), false);
                    }
                    break;
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

    /**
     * True when {@code t} (after stripping non-null wrappers) is a list. Package-visible so
     * {@link ServiceCatalog#inferBindingsByType} computes a depth-1 step's {@code liftsList}
     * flag through the identical predicate {@link #of} uses, keeping an inferred
     * {@link PathExpr.Step} byte-identical to the hand-written one.
     */
    static boolean isListShaped(GraphQLInputType t) {
        GraphQLType current = t;
        while (current instanceof GraphQLNonNull nn) {
            current = nn.getWrappedType();
        }
        return current instanceof GraphQLList;
    }

    /**
     * Parses {@code raw} as a comma-separated list of {@code javaParam: dotted.path} entries.
     * Whitespace (including newlines for text-block input) and commas are insignificant
     * between entries (standard GraphQL convention; the lexer in {@link GraphQLSelectionParser}
     * already handles this). Empty/null/blank input returns an {@link ParsedArgMapping.Ok}
     * with an empty map (identity-for-every-parameter).
     *
     * <p>Single-name overrides (e.g. {@code "inputs: input"}) parse to a one-element
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

    /**
     * Sigil-aware overload: routes each entry's raw right-hand side through
     * {@link ArgMappingSigil#scan} before delegating the residual to
     * {@code GraphQLSelectionParser.parseEntries} (whose lexer rejects {@code $}-prefixed
     * values, so the scan runs on the raw string; {@code parseEntries} itself is untouched).
     * Every argMapping site routes through here with its {@link ArgMappingSigil.Site};
     * columnMapping sites stay on the single-arg overload, since no sigil is admitted there.
     * A recognized sigil at the admitted site lands in {@link ParsedArgMapping.Ok#sigilBindings};
     * a duplicate Java parameter across the sigil and override maps is the same duplicate-entry
     * rejection either way.
     */
    static ParsedArgMapping parseArgMapping(String raw, ArgMappingSigil.Site site) {
        var scanned = ArgMappingSigil.scan(raw, site);
        if (scanned instanceof ArgMappingSigil.ScanResult.Rejected rejected) {
            return new ParsedArgMapping.ParseError(rejected.message());
        }
        var ok = (ArgMappingSigil.ScanResult.Ok) scanned;
        String residual = ok.residual();
        var parsed = residual == null || residual.isBlank()
            ? new ParsedArgMapping.Ok(Map.of())
            : parseArgMapping(residual);
        if (!(parsed instanceof ParsedArgMapping.Ok parsedOk)) {
            return parsed;
        }
        for (String javaName : ok.sigilBindings().keySet()) {
            if (parsedOk.overrides().containsKey(javaName)) {
                return new ParsedArgMapping.ParseError(
                    "argMapping has duplicate entries for Java parameter '" + javaName
                    + "' — each Java parameter may appear at most once");
            }
        }
        return new ParsedArgMapping.Ok(parsedOk.overrides(), ok.sigilBindings());
    }

    /**
     * Renders a name set the way every {@code argMapping} diagnostic renders one, so the routine
     * resolver's own "available arguments are …" clause reads identically to {@link #of}'s.
     */
    static String formatNameSet(Set<String> names) {
        if (names.isEmpty()) return "[]";
        return names.stream().sorted().collect(java.util.stream.Collectors.joining("', '", "['", "']"));
    }
}
