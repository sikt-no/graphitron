package no.sikt.graphitron.rewrite.model;

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
     * <p>The generator emits {@code .join(targetTable).onKey(fk)} for this step. Whether it is an
     * INNER or LEFT JOIN depends on the surrounding query structure (see the interface-level
     * cardinality invariant). The target table and FK constant are derived at code-generation time
     * from the stored SQL name and FK constraint name using jOOQ's naming conventions.
     *
     * <p>{@code fkName} is the SQL constraint name (e.g. {@code "film_language_id_fkey"}), used to
     * look up the jOOQ FK constant (e.g. {@code Keys.FK_FILM__FILM_LANGUAGE_ID_FKEY}).
     * {@code targetTableSqlName} is the SQL name of the table this step navigates <em>to</em>
     * (e.g. {@code "language"} when traversing film → language, or {@code "film"} when traversing
     * in reverse). The source table is always known from the previous step or from the parent type;
     * only the destination needs to be stored. The builder validates that each FK step actually
     * connects to the current source table, catching broken chains at build time.
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
        String targetTableSqlName,
        MethodRef whereFilter
    ) implements JoinStep {}

    /**
     * One hop navigated by a user-supplied condition method (no FK constraint involved).
     *
     * <p>The condition method becomes the ON clause of an explicit join: the generator emits
     * {@code .join(targetTable).on(condition(sourceAlias, targetAlias))}. Used when there is no
     * database foreign key for this join step. Typical use: reconnecting a service or
     * {@code @externalField} result back to the parent table when no FK exists.
     *
     * <p>Contrast with {@link FkJoin#whereFilter}: that field is a WHERE clause on the enclosing
     * SELECT; this condition is the JOIN's ON clause.
     */
    record ConditionJoin(MethodRef condition) implements JoinStep {}
}
