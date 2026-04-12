package no.sikt.graphitron.rewrite.model;

/**
 * One step in the join path expressed by a {@code @reference} directive.
 *
 * <p>The path is an ordered sequence of hops navigating from the parent table to the target table.
 * All steps are fully resolved at build time; an unresolvable step causes the containing field to
 * be classified as {@link no.sikt.graphitron.rewrite.model.GraphitronField.UnclassifiedField}.
 *
 * <ul>
 *   <li>{@link FkJoin} — navigate via a jOOQ foreign key, using {@code .join(table).onKey(fk)},
 *       with an optional WHERE filter applied after the join.</li>
 *   <li>{@link ConditionJoin} — navigate via a user-supplied condition method (no FK constraint);
 *       the condition is the ON clause of the join.</li>
 * </ul>
 */
public sealed interface JoinStep permits JoinStep.FkJoin, JoinStep.ConditionJoin {

    /**
     * One hop navigated by a jOOQ foreign key.
     *
     * <p>The generator emits {@code .join(targetTable).onKey(fk)} for this step. The target table
     * and FK constant are derived at code-generation time from the stored SQL names and FK constraint
     * name using jOOQ's naming conventions.
     *
     * <p>{@code fkName} is the SQL constraint name (e.g. {@code "film_language_id_fkey"}), used to
     * look up the jOOQ FK constant (e.g. {@code Keys.FK_FILM__FILM_LANGUAGE_ID_FKEY}).
     * {@code keyTableSqlName} is the SQL name of the referenced (key-side) table (e.g.
     * {@code "language"}). {@code fkTableSqlName} is the SQL name of the referencing (FK-side)
     * table (e.g. {@code "film"}).
     *
     * <p>{@code whereFilter} is an optional user-supplied condition method resolved from a
     * {@code condition} argument on the same {@code @reference} path element as the {@code key}.
     * When present the generator appends a {@code .where()} / {@code .and()} filter after the join,
     * passing both the source-table alias and the newly-joined table alias as arguments. This is
     * {@code null} when no {@code condition} argument was specified alongside the key.
     */
    record FkJoin(
        String fkName,
        String keyTableSqlName,
        String fkTableSqlName,
        MethodRef whereFilter
    ) implements JoinStep {}

    /**
     * One hop navigated by a user-supplied condition method (no FK constraint involved).
     *
     * <p>The condition method is the ON clause of the join: the generator emits
     * {@code .join(targetTable).on(condition(sourceAlias, targetAlias))}. Used when there is no
     * database foreign key for this join step. Typical use: reconnecting a service or
     * {@code @externalField} result back to the parent table when no FK exists.
     */
    record ConditionJoin(MethodRef condition) implements JoinStep {}
}
