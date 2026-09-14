package no.sikt.graphitron.model.lint;

import graphql.language.SourceLocation;
import no.sikt.graphitron.model.diagnostics.BuildWarning;
import no.sikt.graphitron.model.read.StoreHandle;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;

import static no.sikt.graphitron.model.Tables.GRAPHITRON_FIELD;
import static no.sikt.graphitron.model.Tables.INTENT_FIELD_REFERENCE_STEP_FANOUT;

/**
 * The fan-out advisory, read off {@code intent_field_reference_step_fanout}: a list field whose
 * {@code @reference} path passes <em>through</em> a table that can hold more than one row per pair
 * of columns the entering and the leaving hop bind gets a warning naming the hop that multiplies.
 *
 * <p>A {@code @reference} path is mechanical foreign-key traversal and a SQL join produces a bag,
 * so the duplicates are the declared path's correct result. What was missing is any signal that
 * the path has that property, and a field named for a set reads as one. So this changes no emitted
 * SQL: it says what the SQL does, and the remedy it suggests is a relation with set semantics
 * rather than a flag that changes what the join means.
 *
 * <p>The predicate lives wholly in the view's SQL and what remains here is the decode, which is
 * {@code AuthoredClaimConflicts}' division of labour and has a second consequence worth stating:
 * the relations this rule reads are the view's own declared reads, inside the declaration
 * machinery the store's gates walk, rather than a set this class could name incompletely. The
 * verdict decode is total over the view's vocabulary with no {@code default} and a drift throw,
 * modelled on {@code UnlowerableOrderings.Verdict.of}; two of its four arms deliberately mint
 * nothing, being the view's way of saying it declined to judge rather than that it found nothing.
 *
 * <p>A producer rather than a lint visitor, and the read shape is what decides it. The view it
 * drives from sits on a {@code WITH RECURSIVE} walk carrying window terms, which the fact model
 * requires be taken once per answer and paired on its key rather than correlated per driving row;
 * a visitor at field grain would correlate it once per field. It is folded into the build's one
 * warning-assembly point beside the codegen advisories, so it inherits the suppression filter, the
 * editor replay and the MCP projection with nothing moved.
 */
public final class ReferencePathFanout {

    private ReferencePathFanout() {}

    /** What the view's verdict column says about one intermediate: its own closed vocabulary. */
    public enum Verdict {
        /**
         * No PRIMARY KEY or UNIQUE constraint on the intermediate has every one of its columns
         * among the ones the two hops bind, so the table may hold several rows per bound pair.
         */
        FANS_OUT,
        /** One does, so the hop is one row in and one row out on the pair the two hops bind. */
        COVERED,
        /**
         * The entering or the leaving element joins on an authored Java predicate, whose columns
         * no catalog row names, so one side of the bound set is unreadable.
         */
        UNDECIDABLE_CONDITION_HOP,
        /**
         * One of the two elements departs a table-valued function's result, which declares no
         * constraint to read.
         */
        UNDECIDABLE_NAME_MATCH_HOP;

        /** The verdict a store row carries; an unknown value is vocabulary drift, a build bug. */
        static Verdict of(String verdict) {
            return Arrays.stream(values())
                .filter(v -> v.name().equals(verdict))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                    "the reference-path fan-out view produced verdict '" + verdict + "', which no "
                    + Verdict.class.getSimpleName()
                    + " value names; the view arms and the enum must move together"));
        }
    }

    /**
     * Every finding for {@code store}'s graph, in coordinate order. The population is the list
     * fields: a scalar field over a fanning path lowers to a capped correlated subselect, so it
     * picks an arbitrary row rather than repeating one, which is a different defect with a
     * different remedy and not this rule's to word.
     *
     * <p>{@code excluded} is the consumer's {@code excludedTypes} globs, applied here for the same
     * reason the engine applies them before its walk: this finding lands at a coordinate on a type
     * the consumer asked not to be linted, and firing there would read as a bug. The matching is
     * {@link ExcludedTypes}', shared with the engine rather than restated.
     */
    public static List<BuildWarning.LintFinding> findings(StoreHandle store, ExcludedTypes excluded) {
        Objects.requireNonNull(store, "store");
        Objects.requireNonNull(excluded, "excluded");
        var v = INTENT_FIELD_REFERENCE_STEP_FANOUT;
        var f = GRAPHITRON_FIELD;
        return store.dsl()
            .select(v.TYPE_NAME, v.FIELD_NAME, v.POSITION, v.TABLE_SCHEMA, v.TABLE_NAME,
                v.VERDICT, v.SOURCE_NAME, v.SOURCE_LINE, v.SOURCE_COLUMN)
            .from(v)
            .join(f).on(f.GRAPH_NAME.eq(v.GRAPH_NAME), f.TYPE_NAME.eq(v.TYPE_NAME),
                f.FIELD_NAME.eq(v.FIELD_NAME))
            .where(v.GRAPH_NAME.eq(store.graphName()), f.IS_LIST.isTrue())
            .orderBy(v.TYPE_NAME, v.FIELD_NAME, v.ORDINAL, v.POSITION)
            .fetch()
            .stream()
            .filter(row -> !excluded.matches(row.value1()))
            .filter(row -> mints(Verdict.of(row.value6())))
            .map(row -> finding(row.value1(), row.value2(), row.value3(), row.value4(),
                row.value5(), location(row.value7(), row.value8(), row.value9())))
            .toList();
    }

    /**
     * Whether a verdict words a finding: a total switch over the view's vocabulary with no
     * {@code default}, so an arm added to the view stops compiling here instead of falling into a
     * silence. Three of the four mint nothing, for two different reasons.
     */
    private static boolean mints(Verdict verdict) {
        return switch (verdict) {
            case FANS_OUT -> true;
            // One row in and one row out: the list is a set already and there is nothing to say.
            case COVERED -> false;
            // The view declined to judge this intermediate, and a warning resting on a predicate
            // one of whose sides is unreadable would be a guess. The row exists so that this
            // silence is a decline rather than an absence; the user documentation says in the
            // author's own terms where the rule stops looking, which is where they most need it.
            case UNDECIDABLE_CONDITION_HOP, UNDECIDABLE_NAME_MATCH_HOP -> false;
        };
    }

    /**
     * The message and its fix. The fix carries no edits: there is no mechanical rewrite from a
     * fanning path to a set, so what it offers an editor is the pattern's name, which is the
     * register {@link LintFix} reserves for a suggestion the build never performs.
     */
    private static BuildWarning.LintFinding finding(String typeName, String fieldName, int position,
                                                    String schema, String table,
                                                    SourceLocation location) {
        String qualified = schema + "." + table;
        String message = "Field '" + typeName + "." + fieldName + "' returns duplicate rows: its"
            + " @reference path passes through " + qualified + " at element " + position
            + ", and no primary key or unique constraint there has all its columns among the ones"
            + " the elements either side of it join on, so that table may hold several rows per"
            + " pair they bind. The duplicates are what the declared path means; if the field is"
            + " meant to be a set, traverse a relation with set semantics instead.";
        var fix = new LintFix(
            "Traverse a view over " + qualified + " selecting the bound columns DISTINCT, with a"
            + " synthetic key for the path to join on", List.of());
        return BuildWarning.LintFinding.of(message, location, LintRule.REFERENCE_PATH_FANS_OUT, fix);
    }

    private static SourceLocation location(String sourceName, Integer line, Integer column) {
        if (line == null || column == null) {
            return null;
        }
        return new SourceLocation(line, column, sourceName);
    }
}
