package no.sikt.graphitron.rewrite.model;

/**
 * The table node a {@link JoinStep.Hop} joins to — the <em>target</em> axis of the two-axis
 * step model (R333): a step is a target node materialized by a table expression, plus an
 * {@link On} describing how the step joins to it. The two facts are orthogonal; this seal
 * carries only the node.
 *
 * <ul>
 *   <li>{@link Catalog} — the static generated table reference from the jOOQ catalog, aliased
 *       per hop by the emitters ({@code Tables.<T>.as(alias)}).</li>
 *   <li>{@link RoutineCall} — a table-valued function call (R435): the node's rows are produced
 *       by invoking the schema's generated {@code Routines} convenience method with the bound
 *       IN parameters, then aliased like any table.</li>
 * </ul>
 *
 * <p>Further arms land with their pulling consumers rather than being minted unpopulated:
 * {@code MethodCall} with the {@code @tableMethod} rewire.
 */
public sealed interface TableExpr permits TableExpr.Catalog, TableExpr.RoutineCall {

    /**
     * A statically generated jOOQ catalog table. {@code table} carries the resolved
     * {@link TableRef} (table class, constants class, SQL name) the emitters alias and join.
     */
    record Catalog(TableRef table) implements TableExpr {
        public Catalog {
            if (table == null) {
                throw new NullPointerException("TableExpr.Catalog.table must not be null");
            }
        }
    }

    /**
     * A jOOQ table-valued function call (R435): the routine node of an order-significant
     * {@code @routine} / {@code @reference} chain. jOOQ generates the function as a first-class
     * catalog {@code Table<R>}, so the node has a real {@link TableRef} identity
     * ({@code resultTable}) that answers {@link JoinStep.Hop#targetTable()} — alias generation,
     * terminus checks, and {@code $fields} projection all read the result table uniformly with
     * {@link Catalog} nodes.
     *
     * <p>{@code routine} carries the call surface ({@code Routines} class, table-form
     * convenience method, IN-parameter bindings). A {@link ParamSource.SourceColumn} binding
     * makes the call correlated: the emitters render every argument as a jOOQ {@code Field}
     * ({@code DSL.val(...)} for value-sourced bindings, the previous node's aliased column for
     * column-sourced ones), which selects the generated method's {@code Field}-overload at
     * javac overload resolution — no separate catalog resolution of that overload is needed.
     */
    record RoutineCall(RoutineRef routine, TableRef resultTable) implements TableExpr {
        public RoutineCall {
            if (routine == null) {
                throw new NullPointerException("TableExpr.RoutineCall.routine must not be null");
            }
            if (resultTable == null) {
                throw new NullPointerException("TableExpr.RoutineCall.resultTable must not be null");
            }
        }
    }
}
