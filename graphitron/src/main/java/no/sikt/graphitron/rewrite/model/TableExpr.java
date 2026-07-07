package no.sikt.graphitron.rewrite.model;

/**
 * The table node a {@link JoinStep.Hop} joins to — the <em>target</em> axis of the two-axis
 * step model (R333): a step is a target node materialized by a table expression, plus an
 * {@link On} describing how the step joins to it. The two facts are orthogonal; this seal
 * carries only the node.
 *
 * <p>Day one, only the {@link Catalog} arm exists: the static generated table reference from
 * the jOOQ catalog, aliased per hop by the emitters ({@code Tables.<T>.as(alias)}). Further
 * arms are declared destinations that land with their pulling consumers rather than being
 * minted unpopulated: {@code MethodCall} with the {@code @tableMethod} rewire, and
 * {@code RoutineCall} with R435's routine table nodes.
 */
public sealed interface TableExpr permits TableExpr.Catalog {

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
}
