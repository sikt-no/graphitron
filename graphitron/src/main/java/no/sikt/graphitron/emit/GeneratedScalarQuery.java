package no.sikt.graphitron.emit;

import no.sikt.graphitron.model.read.StoreHandle;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import static no.sikt.graphitron.model.Tables.GRAPHITRON_AST_GENERATED_SCALAR_ENTRY;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_SCALAR_CLASS;
import static no.sikt.graphitron.model.Tables.GRAPHQL_DIRECTIVE_APPLICATION;

/**
 * {@link GeneratedScalarEmitter}'s query: every scalar of the graph Graphitron generates itself, with
 * its kind. A scalar is generated where {@code graphitron_scalar_class} answers it from
 * {@code @generatedScalar}, and its kind is that application's entry row; a scalar carrying both
 * {@code @scalarType} and {@code @generatedScalar} has no class row, so it is not here, and the graph
 * never reaches an emitter anyway, the two claims being a stored defect.
 *
 * <p>The walk reads the names too: it classifies a generated scalar from them rather than deciding
 * one, and its schema class leaves their registration to the emitted unit.
 *
 * <p>Cost: one row per generated scalar, small by construction.
 */
public final class GeneratedScalarQuery {

    private GeneratedScalarQuery() {}

    /** One generated scalar: the author's name for it and the {@code GeneratedScalarKind} it is. */
    public record Scalar(String name, String kind) {
        public Scalar {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(kind, "kind");
        }
    }

    /** The graph's generated scalars, in name order. */
    public record Rows(List<Scalar> scalars) {
        public Rows {
            scalars = List.copyOf(scalars);
        }

        public static Rows empty() {
            return new Rows(List.of());
        }

        /** The generated scalars' names. */
        public Set<String> names() {
            return scalars.stream().map(Scalar::name).collect(Collectors.toUnmodifiableSet());
        }
    }

    public static Rows read(StoreHandle store) {
        var sc = GRAPHITRON_SCALAR_CLASS;
        var da = GRAPHQL_DIRECTIVE_APPLICATION;
        var e = GRAPHITRON_AST_GENERATED_SCALAR_ENTRY;
        var rows = store.dsl()
            .select(sc.TYPE_NAME, e.KIND)
            .from(sc)
            .join(da).on(da.GRAPH_NAME.eq(sc.GRAPH_NAME), da.COORDINATE.eq(sc.TYPE_NAME),
                da.DIRECTIVE_NAME.eq("generatedScalar"), da.ORDINAL.eq(0))
            .join(e).on(e.GRAPH_NAME.eq(da.GRAPH_NAME), e.SOURCE_NAME.eq(da.SOURCE_NAME),
                e.SOURCE_LINE.eq(da.SOURCE_LINE), e.SOURCE_COLUMN.eq(da.SOURCE_COLUMN))
            .where(sc.GRAPH_NAME.eq(store.graphName()), sc.SOURCE.eq("GENERATED"))
            .orderBy(sc.TYPE_NAME)
            .fetch(r -> new Scalar(r.get(sc.TYPE_NAME), r.get(e.KIND)));
        return new Rows(rows);
    }
}
