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
     * <h4>The key-column segment</h4>
     *
     * <p>A path may spell one segment past an {@code ID}, naming a key column of the node type that
     * {@code ID}'s {@code @nodeId} refers to; see {@link #opensIntoNodeKey}. It resolves to an
     * ordinary trailing {@link PathExpr.Step}, which is the whole of the walk's contribution: the
     * segment is <em>admitted and carried, never interpreted</em> here. Two things follow, and both
     * are load-bearing.
     *
     * <p>Interpreting it is a functional dependency of the pair's coordinate resolved against the
     * node type's key columns, so it belongs to the store's resolution and to the plan that reads
     * it, not to a walk that is minting a binding for a different coordinate. The walk cannot even
     * consult that resolution: the schema is built before capture runs, so the store is empty while
     * this executes, and a walk-local re-check would be a second spelling of the view's answer.
     *
     * <p>What keeps an uninterpreted segment safe is therefore the pipeline order rather than a gate
     * here. Capture and validation both run before planning and rendering, so a trailing segment
     * that resolves to no key column fails the build before any emitter sees the path. That makes
     * the admission's safety exactly the completeness of those detections, which is why the store
     * arm covering an {@code ID} with no {@code @nodeId} on it exists at all: without it this
     * factory would hand an emitter a segment nothing had judged.
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
                    if (!opensIntoNodeKey(walkType)) {
                        return new Result.PathRejected(
                            "argMapping entry '" + javaTarget + ": " + dottedPath
                            + "' opens " + describeKind(walkType) + " at segment '"
                            + segments.get(i - 1) + "', which has nothing to open; "
                            + OPENABLE_KINDS);
                    }
                    if (isListShaped(currentFieldType)) {
                        return new Result.PathRejected(
                            "argMapping entry '" + javaTarget + ": " + dottedPath
                            + "' opens a list of ID at segment '" + segments.get(i - 1)
                            + "'; a node id opens into one key column, and a list of node ids has"
                            + " no single key to project a column out of");
                    }
                    if (i != segments.size() - 1) {
                        return new Result.PathRejected(
                            "argMapping entry '" + javaTarget + ": " + dottedPath
                            + "' opens the ID at segment '" + segments.get(i - 1) + "' with '"
                            + segName + "', but a node id opens into exactly one key column, so"
                            + " nothing may follow it");
                    }
                    expr = PathExpr.step(expr, segName, false);
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
     * The two things a dot may open, as a message states them. One clause rather than two rules:
     * the separator has always meant "open the thing at this position", and what a thing opens
     * into follows from what it is.
     */
    private static final String OPENABLE_KINDS =
        "an input object opens into its fields, and an ID carrying @nodeId opens into the key"
        + " columns of the node type it names";

    /**
     * Whether a dot at this position opens a node key: the type is the {@code ID} scalar, which a
     * {@code @nodeId} may sit on and whose decoded key tuple a trailing segment names a column of.
     *
     * <p>Deliberately structural, and deliberately not a test for the directive itself. The head of
     * a path is a slot reached through {@code slotTypes}, which carries types and not directives, so
     * an argument head could not be asked the question at all and a rule the two path positions
     * answered differently would be worse than one they share. What the widening therefore admits is
     * an {@code ID} that opens, whether or not a decode was declared on it; an {@code ID} carrying no
     * {@code @nodeId} is rejected by the store's own detection over the captured leaf, which sees the
     * directive and this does not.
     */
    private static boolean opensIntoNodeKey(GraphQLType walkType) {
        return walkType instanceof GraphQLScalarType s
            && s.getName().equals(graphql.Scalars.GraphQLID.getName());
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
