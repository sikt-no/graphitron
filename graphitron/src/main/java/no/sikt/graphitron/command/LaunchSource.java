package no.sikt.graphitron.command;

import no.sikt.graphitron.rewrite.model.JoinStep;
import no.sikt.graphitron.rewrite.model.TableExpr;
import no.sikt.graphitron.rewrite.model.TableRef;

import java.util.List;
import java.util.Objects;

/**
 * How a launcher's rows are sourced and projected: the FROM clause's origin and the select
 * list's derivation, one axis because the two co-vary (a routine chain's projection targets the
 * terminus alias, never a declared table local; the coming discriminated arm has no single
 * projection unit at all). Absorbing both facts into the arm keeps the illegal cells
 * unrepresentable and spares the command a table slot whose meaning would change per arm.
 */
public sealed interface LaunchSource {

    /**
     * The plain origin: one {@code @table}-bound anchor, declared as the body's table local,
     * projected through the one {@link #projection} unit's {@code $project}.
     */
    record AnchorTable(TableRef table, UnitRef projection) implements LaunchSource {
        public AnchorTable {
            Objects.requireNonNull(table, "table");
            Objects.requireNonNull(projection, "projection");
        }
    }

    /**
     * A {@code @routine} chain: the FROM source is the bound table-valued function (the start
     * expression, IN parameters bound from field arguments), hops join forward out of the
     * routine result, and {@link #projection} targets the <em>terminus</em> (the last hop's
     * alias, or the start itself when hop-less; the classifier's terminus invariant pins the
     * projected type to the chain's last node). The hop list is the narrowed
     * {@link JoinStep.Hop} form the model's chain constructor guarantees, so the renderer never
     * casts.
     */
    record RoutineChain(TableExpr.RoutineCall start, List<JoinStep.Hop> hops, UnitRef projection)
            implements LaunchSource {
        public RoutineChain {
            Objects.requireNonNull(start, "start");
            hops = List.copyOf(hops);
            Objects.requireNonNull(projection, "projection");
        }
    }
}
