package no.sikt.graphitron.rewrite.model;

import java.util.List;

/**
 * One step in the join path expressed by a {@code @reference} directive.
 *
 * <p>The path is an ordered sequence of hops navigating from the parent table to the target table.
 * All steps are fully resolved at build time; an unresolvable step causes the containing field to
 * be classified as {@link no.sikt.graphitron.rewrite.model.GraphitronField.UnclassifiedField}.
 *
 * <ul>
 *   <li>{@link FkJoin} — navigate via a jOOQ foreign key ({@code .join(table).onKey(fk)}),
 *       with an optional WHERE filter on the enclosing SELECT.</li>
 *   <li>{@link ConditionJoin} — navigate via a user-supplied condition method (no FK constraint);
 *       the condition becomes the ON clause of an explicit JOIN.</li>
 * </ul>
 *
 * <p><b>Variant contrast:</b>
 * <pre>
 *   FkJoin (key only):          .join(target).onKey(FK)
 *   FkJoin (key + whereFilter): .join(target).onKey(FK) ... .where(filter(src, target))
 *   ConditionJoin:              .join(target).on(condition(src, target))
 * </pre>
 * {@code whereFilter} is a WHERE clause on the enclosing SELECT — it does not affect the JOIN's
 * ON clause. {@link ConditionJoin#condition} is the ON clause.
 *
 * <h2>Cardinality invariant</h2>
 *
 * <p>A {@code @reference} join path must never change the cardinality of the source row set.
 * Two structural rules enforce this:
 *
 * <ol>
 *   <li><b>No row elimination.</b> A source row must always produce at least one output row.
 *       This depends on the query structure the generator chooses:
 *       <ul>
 *         <li><em>Correlated subquery</em> (scalar or multiset subselect): the outer row
 *             survives regardless of whether the inner join matches, so INNER JOIN is safe and
 *             preferred inside the subquery. A non-matching inner join simply makes the subquery
 *             return {@code NULL} or an empty array.</li>
 *         <li><em>Flat batch join</em> (DataLoader / split query): all source keys must appear
 *             in the result set so the DataLoader can align results to keys. INNER JOIN would
 *             silently drop source rows where the FK is {@code NULL}. LEFT JOIN is mandatory.</li>
 *       </ul>
 *       The join type is therefore a <em>generation-time decision</em> based on query structure,
 *       not a property of the step itself.</li>
 *
 *   <li><b>No unintended row multiplication.</b> Fan-out (one source row producing many output
 *       rows) is only valid when the referencing field returns a list or connection — in which case
 *       fan-out is the <em>intended</em> result, collected and grouped by the DataLoader. For a
 *       single-value field, any fan-out is a schema error. The validator is responsible for
 *       detecting mis-directed FK traversal (one-to-many navigation on a single-value field) and
 *       rejecting it.</li>
 * </ol>
 */
public sealed interface JoinStep permits JoinStep.FkJoin, JoinStep.ConditionJoin {

    /**
     * One hop navigated by a jOOQ foreign key.
     *
     * <p>The generator emits {@code .join(targetAlias).onKey(Keys.FK_...)} for this step. Whether
     * it is an INNER or LEFT JOIN depends on the surrounding query structure (see the interface-level
     * cardinality invariant). All fields are pre-resolved at build time from the jOOQ catalog.
     *
     * <p>{@code fkName} is the SQL constraint name (e.g. {@code "film_language_id_fkey"}), retained
     * for error messages and debugging. {@code fkJavaConstant} is the Java constant name in the
     * generated {@code Keys} class (e.g. {@code "FK_FILM__FILM_LANGUAGE_ID_FKEY"}); it may be
     * empty when the jOOQ catalog is not available (unit tests).
     *
     * <p>{@code sourceColumns} resolves to the columns <em>holding</em> the FK (e.g.
     * {@code film.language_id}); {@code targetTable} / {@code targetColumns} resolve to the
     * table and PK columns the FK <em>points to</em> (e.g. {@code language.language_id}).
     * These are populated at build time from the jOOQ {@code ForeignKey}. Both column lists have
     * equal arity by jOOQ invariant. When the jOOQ catalog is unavailable (unit tests) these
     * fall back to an empty list.
     *
     * <p>{@code sourceTable} is the <em>traversal-origin</em> table of this hop — i.e. the side
     * the join enters <em>from</em>, which is the parent table for hop 0 and the previous hop's
     * target for subsequent hops. This differs from {@code sourceColumns}'s side (the
     * FK-holder): the two can sit on opposite sides of the FK when the join traverses
     * child-to-parent. The field is not a direction signal — readers that need to know which
     * side holds the FK must compare {@code sourceColumns}' owning table against
     * {@code sourceTable}, or infer from the schema context (e.g. {@code @splitQuery}
     * cardinality ⇒ FK direction; see {@code FieldBuilder.deriveSplitQueryBatchKey}). See the
     * roadmap entry "Clarify {@code FkJoin} direction semantics" for the follow-up that
     * straightens this out. Falls back to an empty {@link TableRef} when the jOOQ catalog is
     * unavailable.
     *
     * <p>{@code alias} is the unique table alias for this step within the enclosing query, computed
     * at build time as {@code fieldName + "_" + stepIndex} (e.g. {@code "language_0"} for the
     * first step of a {@code language} field). The alias is unique per field × depth, which handles
     * self-referential join paths where the same table appears multiple times.
     *
     * <p>{@code whereFilter} is an optional user-supplied condition method resolved from a
     * {@code condition} argument on the same {@code @reference} path element as the {@code key}.
     * When present, the generator appends it as a {@code .where()} or {@code .and()} clause on the
     * enclosing SELECT — <em>not</em> on the JOIN's ON clause. The method receives the source-table
     * alias and the newly-joined table alias as its two arguments, in that order. This field is
     * {@code null} when no {@code condition} argument was specified alongside the key.
     */
    record FkJoin(
        String fkName,
        String fkJavaConstant,
        TableRef sourceTable,
        List<ColumnRef> sourceColumns,
        TableRef targetTable,
        List<ColumnRef> targetColumns,
        MethodRef whereFilter,
        String alias
    ) implements JoinStep {}

    /**
     * One hop navigated by a user-supplied condition method (no FK constraint involved).
     *
     * <p>The condition method becomes the ON clause of an explicit join: the generator emits
     * {@code .join(targetAlias).on(condition(sourceAlias, targetAlias))}. Used when there is no
     * database foreign key for this join step. Typical use: reconnecting a service or
     * {@code @externalField} result back to the parent table when no FK exists.
     *
     * <p>{@code alias} is the unique table alias for this step, computed at build time as
     * {@code fieldName + "_" + stepIndex}. The target table is not pre-resolved here — condition
     * method resolution (P3) will provide it once reflection over the method signature is
     * implemented.
     *
     * <p>Contrast with {@link FkJoin#whereFilter}: that field is a WHERE clause on the enclosing
     * SELECT; this condition is the JOIN's ON clause.
     */
    record ConditionJoin(MethodRef condition, String alias) implements JoinStep {}
}
