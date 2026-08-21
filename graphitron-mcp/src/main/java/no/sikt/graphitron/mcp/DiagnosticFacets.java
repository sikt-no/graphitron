package no.sikt.graphitron.mcp;

import io.modelcontextprotocol.spec.McpSchema;
import no.sikt.graphitron.model.boot.StoreAnswer;
import no.sikt.graphitron.model.read.SourceUri;
import no.sikt.graphitron.model.read.StoreHandle;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Record;
import org.jooq.SortField;
import org.jooq.impl.DSL;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

import static no.sikt.graphitron.model.Tables.DIAGNOSTIC;

/**
 * The faceted diagnostics aggregate and the shared filter mechanism under it: the closed
 * dimension vocabulary (one wire name per {@code diagnostic} view column), the null-safe
 * {@code where} translation both diagnostics tools share, and the counts-only
 * {@code diagnostics.aggregate} computation. This is the module's first read of the fact store;
 * the handle arrives through {@link StoreHandle} from the dev session's one
 * store owner, never from a file the reader opened itself, and every statement is scoped to the
 * reading session's graph.
 *
 * <p>The dimension set is a closed enum rather than a query surface on purpose: an agent
 * discovers it from the tool's input schema in one shot, a name that is not in it fails with the
 * full vocabulary instead of a parse error, and the server keeps the result guarantees (exact
 * counts, the stated tail rule, the zero-argument triage preset) that a raw query could not be
 * made to keep on the caller's behalf. Every dimension is one single-valued view column, so
 * every {@code groupBy} groups at one row per diagnostic and group counts sum to the row count;
 * absence is uniformly SQL {@code NULL} and every comparison is {@code IS NOT DISTINCT FROM},
 * so {@code where} and {@code groupBy} cannot disagree about the absent bucket.
 *
 * <p>The bet covers the argument values too, in the two shapes the ownership of a vocabulary
 * allows. A value drawn from a vocabulary this module owns, a dimension name or an
 * {@link Ordering}, is refused with the whole set named. A value drawn from a column taxonomy the
 * store owns is read into the {@link Spelling} that column is stored in, refusing there meaning
 * copying a value set this module does not define and that grows as detections migrate. Neither
 * shape silently answers a different question than the one asked: a filter value outside the data
 * is a question with an honest empty answer, while an ordering outside the two is a different
 * answer to the question asked, which is why one normalises and the other refuses.
 *
 * <p>{@code messageTemplate} is deliberately not a dimension: a template groups on the
 * rendering rather than the fact, its substrate (the two {@code Structural} catch-alls and the
 * advisory arm) shrinks as detections take over rejection families, and the surviving residue
 * reads off {@code variant} alone. Advisory rows carry no typed dimension at all, so on any
 * typed {@code groupBy} they collapse into one NULL-keyed bucket and group by location only,
 * which the dimension gloss states outright.
 */
final class DiagnosticFacets {

    private DiagnosticFacets() {}

    /** Default groups per aggregate response; small enough that the result never rivals the entry list. */
    static final int DEFAULT_GROUP_LIMIT = 40;

    /**
     * The stated cardinality cap: however large the requested {@code limit}, an aggregate never
     * returns more groups than this, and the elision accounting reports what the cap folded. This
     * is the guard against a composite {@code groupBy} over high-cardinality dimensions producing
     * a response larger than the entry list it exists to replace.
     */
    static final int MAX_GROUP_LIMIT = 200;

    /** Default example coordinates (and files) per group. */
    static final int DEFAULT_EXAMPLES = 2;

    /** Upper bound on per-group examples; the drill-down reads full clusters, not this sample. */
    static final int MAX_EXAMPLES = 10;

    /**
     * One pivot dimension: a wire name, the {@code diagnostic} view column it groups and filters
     * on, and the parenthetical gloss the tool description renders. An enum of labels, not a
     * sealed hierarchy, because every value has the identical shape and carries no kind-dependent
     * data.
     */
    enum Dimension {
        SEVERITY("severity", DIAGNOSTIC.SEVERITY, "", Spelling.LOWER_CASE),
        SOURCE("source", DIAGNOSTIC.SOURCE, "", Spelling.LOWER_CASE),
        ACTIONABLE("actionable", DIAGNOSTIC.ACTIONABLE, "can you fix this in the schema?"),
        KIND("kind", DIAGNOSTIC.KIND, "", Spelling.UPPER_SNAKE),
        VARIANT("variant", DIAGNOSTIC.VARIANT, "the rejection's own class"),
        LSP_CODE("lspCode", DIAGNOSTIC.LSP_CODE, ""),
        ATTEMPT_KIND("attemptKind", DIAGNOSTIC.ATTEMPT_KIND,
            "which lookup space a name resolution failed in", Spelling.UPPER_SNAKE),
        ATTEMPT("attempt", DIAGNOSTIC.ATTEMPT, "the name the author wrote"),
        STUB_KEY("stubKey", DIAGNOSTIC.STUB_KEY, ""),
        DIRECTIVES("directives", DIAGNOSTIC.DIRECTIVES,
            "the directive names identifying a conflict, as one value"),
        LINT_RULE("lintRule", DIAGNOSTIC.LINT_RULE, ""),
        COORDINATE("coordinate", DIAGNOSTIC.COORDINATE, "a type or Type.field"),
        TYPE("type", DIAGNOSTIC.TYPE_NAME, ""),
        FILE("file", DIAGNOSTIC.FILE, "", Spelling.FILE_URI),
        DIRECTORY("directory", DIAGNOSTIC.DIRECTORY, "", Spelling.DIRECTORY_URI);

        private final String wireName;
        private final Field<?> column;
        private final String gloss;
        private final Spelling spelling;

        /** A dimension making no spelling claim: the wire value compares as the caller wrote it. */
        Dimension(String wireName, Field<?> column, String gloss) {
            this(wireName, column, gloss, Spelling.AS_STORED);
        }

        Dimension(String wireName, Field<?> column, String gloss, Spelling spelling) {
            this.wireName = wireName;
            this.column = column;
            this.gloss = gloss;
            this.spelling = spelling;
        }

        String wireName() {
            return wireName;
        }

        Field<?> column() {
            return column;
        }

        String glossed() {
            return gloss.isEmpty() ? wireName : wireName + " (" + gloss + ")";
        }

        /**
         * The null-safe filter this dimension contributes: {@code IS NOT DISTINCT FROM}, so a
         * {@code null} value selects the stated absent bucket instead of matching nothing.
         */
        Condition matches(Object wireValue) {
            return matchesStored(coerce(wireValue));
        }

        @SuppressWarnings("unchecked")
        private Condition matchesStored(Object storedValue) {
            return ((Field<Object>) column).isNotDistinctFrom(storedValue);
        }

        /** Coerces a wire value onto the column's own type; boolean for actionable, string otherwise. */
        private Object coerce(Object wireValue) {
            if (wireValue == null) {
                return null;
            }
            if (column.getType() == Boolean.class) {
                if (wireValue instanceof Boolean b) {
                    return b;
                }
                String text = String.valueOf(wireValue).toLowerCase(Locale.ROOT);
                if (text.equals("true") || text.equals("false")) {
                    return Boolean.valueOf(text);
                }
                throw new BadRequest("dimension '" + wireName + "' takes true or false, not '"
                    + wireValue + "'.");
            }
            return normalise(String.valueOf(wireValue));
        }

        /**
         * The wire value read into this dimension's own spelling. Package-private because it is
         * also the pin's subject: the wire spelling and the stored one have to round-trip, or the
         * {@code where} boundary would drop rows the value it published names.
         */
        String normalise(String wireValue) {
            return spelling.normalise(wireValue);
        }

        /**
         * The stored value in this dimension's wire spelling: what a group key, an example file or
         * an entry publishes. The identity for every spelling that stores what it publishes, which
         * is all of them but the file axis, whose columns hold a path where both wires this server
         * answers on name a document by URI.
         */
        Object render(Object storedValue) {
            return storedValue instanceof String text ? spelling.render(text) : storedValue;
        }

        /** Resolves a wire name, failing with the full closed vocabulary rather than a bare miss. */
        static Dimension of(String wireName) {
            for (Dimension dimension : values()) {
                if (dimension.wireName.equals(wireName)) {
                    return dimension;
                }
            }
            throw new BadRequest("unknown dimension '" + wireName + "'; the dimensions are "
                + wireNames() + ".");
        }

        static List<String> wireNames() {
            return java.util.Arrays.stream(values()).map(Dimension::wireName).toList();
        }
    }

    /**
     * How a dimension's wire spelling relates to the spelling its column is stored in: inbound
     * through {@link #normalise}, outbound through {@link #render}. Naming a spelling is a weaker
     * claim than naming a value set: the store owns which severities exist, this owns only that the
     * {@code diagnostic} view writes them in one case, so a spelling cannot go stale when a taxonomy
     * grows. What makes it safe is the round trip, {@code normalise(render(stored))} giving back the
     * stored value, which {@code DiagnosticsAggregateTest} pins against the store's own group keys
     * rather than against this reading; on the spellings that publish what they store the round trip
     * is the identity, which is the same pin stated for the same reason. A group key never enters
     * the filter path: a drill-down re-feeds stored values through {@link Dimension#matchesStored},
     * so the aggregate and its drill-down stay exact whatever is declared here.
     */
    enum Spelling {
        /** No claim; the default, and every open-text or author-written column. */
        AS_STORED,
        /** Lower case, the case the view writes its own {@code severity} and {@code source} literals in. */
        LOWER_CASE,
        /**
         * Upper case with hyphens folded to underscores: an enum {@code name()}, which is what the
         * kind and attempt-kind columns hold. The fold is what lets the kebab-case spelling the
         * {@code diagnostics} entries render ({@code invalid-schema}) read back as a filter.
         */
        UPPER_SNAKE,
        /**
         * A {@code file:} URI on the wire over a stored path. The one spelling whose render is not
         * the identity: the store holds the path a reader spelled, while both protocols this server
         * answers on name a document by URI, so the file axis encodes here and decodes here and the
         * path never leaves the interior.
         *
         * <p>Groups still order by the stored path rather than by the rendered URI, an aggregate
         * applying its limit in SQL where the render is not available. The two orders agree except
         * on a path carrying a character the URI encodes, which reorders against its neighbours.
         */
        FILE_URI,
        /**
         * The directory half of the file axis, rendered as {@link SourceUri#ofDirectory} rather than
         * as a converted path: whether a directory exists on disk decides a trailing slash and has
         * no business on the wire.
         */
        DIRECTORY_URI;

        String normalise(String value) {
            return switch (this) {
                case AS_STORED -> value;
                case LOWER_CASE -> value.toLowerCase(Locale.ROOT);
                case UPPER_SNAKE -> value.toUpperCase(Locale.ROOT).replace('-', '_');
                case FILE_URI, DIRECTORY_URI -> SourceUri.sourceNameOf(value).orElse(value);
            };
        }

        String render(String stored) {
            return switch (this) {
                case AS_STORED, LOWER_CASE, UPPER_SNAKE -> stored;
                case FILE_URI -> SourceUri.of(stored);
                case DIRECTORY_URI -> SourceUri.ofDirectory(stored);
            };
        }
    }

    /**
     * How an aggregate orders its groups: by descending count, which is the triage reading, or by
     * the composite group key. Two values, owned outright by the code that sorts the rows and
     * defined nowhere else, so an unknown one is refused with both named rather than defaulting to
     * the other. Defaulting was the whole defect: an ordering names the shape of the answer, so
     * ignoring it hands back an answer to a question nobody asked with nothing in the response
     * saying so, which is the guess-and-retry cost a closed vocabulary exists to remove.
     */
    enum Ordering {
        COUNT("count"),
        KEY("key");

        private final String wireName;

        Ordering(String wireName) {
            this.wireName = wireName;
        }

        String wireName() {
            return wireName;
        }

        /** Resolves a wire name, failing with both orderings rather than silently picking one. */
        static Ordering of(String wireName) {
            for (Ordering ordering : values()) {
                if (ordering.wireName.equals(wireName)) {
                    return ordering;
                }
            }
            throw new BadRequest("unknown orderBy '" + wireName + "'; the orderings are "
                + wireNames() + ".");
        }

        static List<String> wireNames() {
            return java.util.Arrays.stream(values()).map(Ordering::wireName).toList();
        }
    }

    /**
     * The dimension partition, which is also the documentation's structure: the typed-key bucket
     * reads off the diagnostic's own data and is stable across a message rewording, the
     * location-derived bucket reads off where the diagnostic sits. Ordered lists so the rendered
     * gloss is stable; {@code DiagnosticDimensionCoverageTest} pins that the two buckets
     * partition the enum exactly.
     */
    static final List<Dimension> TYPED_KEY_DIMENSIONS = List.of(
        Dimension.SEVERITY, Dimension.SOURCE, Dimension.ACTIONABLE, Dimension.KIND,
        Dimension.VARIANT, Dimension.LSP_CODE, Dimension.ATTEMPT_KIND, Dimension.ATTEMPT,
        Dimension.STUB_KEY, Dimension.DIRECTIVES, Dimension.LINT_RULE);

    static final List<Dimension> LOCATION_DERIVED_DIMENSIONS = List.of(
        Dimension.COORDINATE, Dimension.TYPE, Dimension.FILE, Dimension.DIRECTORY);

    /**
     * The dimension vocabulary as the tool description documents it, rendered from the partition
     * above so the documentation cannot drift from the declared buckets.
     */
    static String dimensionGloss() {
        return "Dimensions read off the diagnostic's own data, stable across a message rewording: "
            + TYPED_KEY_DIMENSIONS.stream().map(Dimension::glossed).collect(Collectors.joining(", "))
            + ". Dimensions read off the location: "
            + LOCATION_DERIVED_DIMENSIONS.stream().map(Dimension::glossed).collect(Collectors.joining(", "))
            + "; the pairs are coarse and fine grains of one axis, so pick deliberately. "
            + "Absence is a value: rows without a coordinate or file group into one null-keyed "
            + "bucket rather than dropping out of the totals, and advisory warnings, which carry "
            + "no typed dimension at all, group by location only.";
    }

    /** The zero-argument triage preset: the actionable / deferred headline with kind sub-counts. */
    static final List<Dimension> TRIAGE_PRESET = List.of(Dimension.ACTIONABLE, Dimension.KIND);

    /** A caller mistake worth a message, mapped to a tool error rather than a protocol failure. */
    static final class BadRequest extends RuntimeException {
        @java.io.Serial
        private static final long serialVersionUID = 1L;

        BadRequest(String message) {
            super(message);
        }
    }

    // ---- the shared where translation ----

    /**
     * Translates the shared {@code where} argument (a map of dimension wire names to values) into
     * the graph-scoped condition list both diagnostics tools filter by. The graph scope leads:
     * the store is shared by every module of a workspace, so an unscoped read would answer with
     * another graph's diagnostics.
     */
    static List<Condition> conditions(String graphName, Map<String, Object> args) {
        var conditions = new ArrayList<Condition>();
        conditions.add(DIAGNOSTIC.GRAPH_NAME.eq(graphName));
        Object where = args == null ? null : args.get("where");
        if (where == null) {
            return conditions;
        }
        if (!(where instanceof Map<?, ?> map)) {
            throw new BadRequest("'where' must be an object mapping dimension names to values.");
        }
        for (var entry : map.entrySet()) {
            conditions.add(Dimension.of(String.valueOf(entry.getKey())).matches(entry.getValue()));
        }
        return conditions;
    }

    // ---- the refusal and error shapes ----

    /**
     * The handle-less answer: a refusal naming the missing store handle, never an empty answer. An
     * empty result from a missing store reads identically to a real one, so the wiring fact is loud
     * instead: zero diagnostics read as a clean schema and zero tables as a database with no
     * tables. Shared by every tool that answers from the store rather than owned by the diagnostics
     * pair, one wiring failure being one refusal wherever it surfaces. No production path meets
     * this: {@code graphitron:dev} always passes the handle; the store-less boots are test servers.
     *
     * <p>Distinct from a store that holds no rows yet, which is an answer and reports as one.
     */
    static McpSchema.CallToolResult refusal(String tool) {
        return error(tool + ": this server holds no fact store handle, so the store cannot be "
            + "read and an answer will not be fabricated. A dev session "
            + "(mvn graphitron:dev) always wires its session store handle in.");
    }

    /**
     * The out-of-budget answer: an error naming the budget and the statement, never an empty result.
     *
     * <p>The posture is this server's own rather than the language server's, and it does not follow
     * from it. An editor has prior state to keep and a screen to leave standing, so degrading to
     * "nothing new" there costs the developer nothing they were not already looking at. A turn-based
     * server has neither. What it has instead is a caller that reasons about the result, and an
     * agent handed an empty result set concludes the fact does not exist and moves on with that
     * belief. So the read failing has to arrive as a failure, for the same reason
     * {@link #refusal} exists: silence reads as absence.
     *
     * <p>Shared by every tool that reads through a {@link no.sikt.graphitron.model.boot.StoreReader},
     * one boundary overrun being one error wherever it surfaces.
     */
    static McpSchema.CallToolResult outOfBudget(String tool, StoreAnswer.OutOfBudget<?> expired) {
        return error(tool + ": a fact store read ran past its " + expired.budget().describe()
            + " budget and was aborted, so there is no answer to give and an empty one will not be "
            + "fabricated. The statement that overran: " + expired.sql());
    }

    /** The shared tool-error shape ({@code isError} plus a structured status), as the execute tool spells it. */
    static McpSchema.CallToolResult error(String message) {
        var fields = new LinkedHashMap<String, Object>();
        fields.put("status", "error");
        fields.put("message", message);
        return McpSchema.CallToolResult.builder()
            .addTextContent(message)
            .structuredContent(fields)
            .isError(true)
            .build();
    }

    // ---- the aggregate ----

    static McpSchema.CallToolResult aggregateResult(StoreHandle store, Map<String, Object> args) {
        if (store == null) {
            return refusal("diagnostics.aggregate");
        }
        try {
            return aggregate(store, args);
        } catch (BadRequest e) {
            return error("diagnostics.aggregate: " + e.getMessage());
        }
    }

    private static McpSchema.CallToolResult aggregate(StoreHandle store, Map<String, Object> args) {
        DSLContext dsl = store.dsl();
        String graphName = store.graphName();
        List<Dimension> dims = groupByDimensions(args);
        boolean preset = dims == TRIAGE_PRESET;
        List<Condition> base = conditions(graphName, args);
        int minCount = Math.max(McpWire.intArg(args, "minCount", 1), 1);
        int examples = Math.clamp(McpWire.intArg(args, "examples", DEFAULT_EXAMPLES), 0, MAX_EXAMPLES);
        int limit = McpWire.intArg(args, "limit", DEFAULT_GROUP_LIMIT);
        if (limit < 1) {
            limit = DEFAULT_GROUP_LIMIT;
        }
        limit = Math.min(limit, MAX_GROUP_LIMIT);
        Ordering groupOrder = ordering(args);

        List<Field<?>> groupFields = dims.stream().map(Dimension::column).toList();
        Field<Integer> count = DSL.count();
        var selected = new ArrayList<Field<?>>(groupFields);
        selected.add(count);
        var ordering = new ArrayList<SortField<?>>();
        if (groupOrder == Ordering.COUNT) {
            ordering.add(count.desc());
        }
        groupFields.forEach(f -> ordering.add(f.asc().nullsLast()));

        var rows = dsl.select(selected)
            .from(DIAGNOSTIC)
            .where(base)
            .groupBy(groupFields)
            .having(count.ge(minCount))
            .orderBy(ordering)
            .limit(limit)
            .fetch();
        int total = dsl.fetchCount(DIAGNOSTIC, DSL.and(base));
        int totalGroups = dsl.fetchCount(
            dsl.select(groupFields).from(DIAGNOSTIC).where(base).groupBy(groupFields));

        long shownSum = 0;
        var groups = new ArrayList<Map<String, Object>>(rows.size());
        for (Record row : rows) {
            var key = new LinkedHashMap<String, Object>();
            var groupConditions = new ArrayList<>(base);
            for (Dimension dimension : dims) {
                Object value = row.get(dimension.column());
                // The key is rendered and the drill-down filter is not: an agent pastes the key
                // back and it normalises to this same stored value, while the filter under this
                // group never leaves the stored spelling and so cannot lose a row to a round trip.
                key.put(dimension.wireName(), dimension.render(value));
                groupConditions.add(dimension.matchesStored(value));
            }
            int groupCount = row.get(count);
            shownSum += groupCount;
            var group = new LinkedHashMap<String, Object>();
            group.put("key", key);
            group.put("count", groupCount);
            if (examples > 0) {
                group.put("examples", dsl.selectDistinct(DIAGNOSTIC.COORDINATE)
                    .from(DIAGNOSTIC)
                    .where(groupConditions).and(DIAGNOSTIC.COORDINATE.isNotNull())
                    .orderBy(DIAGNOSTIC.COORDINATE.asc())
                    .limit(examples)
                    .fetch(DIAGNOSTIC.COORDINATE));
                group.put("files", dsl.selectDistinct(DIAGNOSTIC.FILE)
                    .from(DIAGNOSTIC)
                    .where(groupConditions).and(DIAGNOSTIC.FILE.isNotNull())
                    .orderBy(DIAGNOSTIC.FILE.asc())
                    .limit(examples)
                    .fetch(DIAGNOSTIC.FILE).stream()
                    .map(Dimension.FILE::render)
                    .toList());
            }
            group.put("fileCount", dsl.fetchCount(dsl.selectDistinct(DIAGNOSTIC.FILE)
                .from(DIAGNOSTIC)
                .where(groupConditions).and(DIAGNOSTIC.FILE.isNotNull())));
            groups.add(group);
        }
        int elidedGroups = totalGroups - groups.size();
        long elidedCount = total - shownSum;

        var fields = new LinkedHashMap<String, Object>();
        fields.put("groupBy", dims.stream().map(Dimension::wireName).toList());
        fields.put("groups", groups);
        fields.put("totalDiagnostics", total);
        fields.put("totalGroups", totalGroups);
        fields.put("elidedGroups", elidedGroups);
        fields.put("elidedCount", elidedCount);
        McpWire.writeSnapshotAxes(fields, SchemaLifecycle.read(store));

        String summary = summarize(dsl, base, dims, preset, total, totalGroups, groups.size(),
            elidedGroups, elidedCount);
        return McpSchema.CallToolResult.builder()
            .addTextContent(summary)
            .structuredContent(fields)
            .build();
    }

    private static String summarize(
        DSLContext dsl, List<Condition> base, List<Dimension> dims, boolean preset,
        int total, int totalGroups, int shownGroups, int elidedGroups, long elidedCount
    ) {
        var summary = new StringBuilder("diagnostics.aggregate: " + total + " diagnostic(s)");
        if (preset) {
            // The triage headline is computed over the whole filtered set, not summed off the
            // shown groups, so it stays exact even when the tail rule elides a group.
            int actionable = dsl.fetchCount(DIAGNOSTIC,
                DSL.and(base).and(DIAGNOSTIC.ACTIONABLE.eq(true)));
            summary.append(": ").append(actionable)
                .append(" actionable (fixable in the schema), ").append(total - actionable)
                .append(" deferred (not yet generator-supported)");
        }
        summary.append("; ").append(shownGroups).append(" of ").append(totalGroups)
            .append(" group(s) by [")
            .append(dims.stream().map(Dimension::wireName).collect(Collectors.joining(", ")))
            .append("]");
        if (elidedGroups > 0 || elidedCount > 0) {
            summary.append("; ").append(elidedGroups).append(" group(s) elided, totalling ")
                .append(elidedCount).append(" diagnostic(s)");
        }
        return summary.append(".").toString();
    }

    /**
     * The requested group ordering, or {@code COUNT} when {@code orderBy} is absent. Read off the
     * argument map the way {@code groupBy} is, rather than through
     * {@link McpWire#stringArg}: that reader answers absent for a blank or non-string value, which
     * for a closed vocabulary is the silent default this refuses to give. Lenient coercion is what
     * the numeric arguments want, and they keep it, a clamped {@code limit} reporting itself
     * through the elision accounting where a defaulted ordering reports nothing.
     */
    private static Ordering ordering(Map<String, Object> args) {
        Object orderBy = args == null ? null : args.get("orderBy");
        return orderBy == null ? Ordering.COUNT : Ordering.of(String.valueOf(orderBy));
    }

    /** The requested dimensions, or the triage preset when {@code groupBy} is absent or empty. */
    private static List<Dimension> groupByDimensions(Map<String, Object> args) {
        Object groupBy = args == null ? null : args.get("groupBy");
        if (groupBy == null) {
            return TRIAGE_PRESET;
        }
        if (!(groupBy instanceof List<?> names)) {
            throw new BadRequest("'groupBy' must be an array of dimension names; the dimensions are "
                + Dimension.wireNames() + ".");
        }
        if (names.isEmpty()) {
            return TRIAGE_PRESET;
        }
        var dims = new ArrayList<Dimension>(names.size());
        for (Object name : names) {
            Dimension dimension = Dimension.of(String.valueOf(name));
            if (dims.contains(dimension)) {
                throw new BadRequest("dimension '" + dimension.wireName() + "' appears twice in groupBy.");
            }
            dims.add(dimension);
        }
        return List.copyOf(dims);
    }
}
