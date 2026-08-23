package no.sikt.graphitron.command;

import graphql.schema.FieldCoordinates;

import java.util.List;
import java.util.Objects;

/**
 * One row of the routine-write command relation: everything the fetcher entry point of one
 * {@code @routine}-writing mutation coordinate emits, as data. Keyed by {@link #coordinate}.
 *
 * <p>Two arms, one per emitted shape, and the fork is the return's: {@link ChainReread} projects
 * the chain's terminus itself after the commit, {@link CarrierKeys} stops at the captured keys
 * and leaves the projection to the payload's data field. Both share the write half, a routine
 * call executed inside the per-field transaction with a projection of its own result columns
 * beside it and nothing else.
 *
 * <p>Every slot is build-time composition, the same static/runtime line the launcher rows draw:
 * the argument values the routine is called with arrive through the rendered method's
 * {@code env}, never through the row. {@link #arity} is the one/many fact deciding the fetch
 * terminal and the declared value type; it is the field's own SDL wrapper on the direct arm and
 * the payload data field's on the carrier arm, which is why it rides the row rather than being
 * re-read from either.
 *
 * <p>Every catalog fact rides as a captured name ({@link CatalogTable}, {@link CatalogColumn},
 * {@link JoinBasis}), never as one of the walk's javapoet-bearing refs. That is the line the
 * package draws: which class a table is emitted as is a fact the store holds, and how that name is
 * spelled into source is the renderer's business. Generated class names ride as refs
 * ({@link UnitRef}, {@link UnitMethodRef}) for the same reason from the other side, the plan's
 * naming vocabulary being their one mint.
 */
public sealed interface RoutineWriteCommand
        permits RoutineWriteCommand.ChainReread, RoutineWriteCommand.CarrierKeys {

    /** The emitted fetcher entry point: its owning fetchers class and its method name. */
    UnitMethodRef unit();

    /** The relation's key: the mutation field this row emits the entry point for. */
    FieldCoordinates coordinate();

    /** The routine call the write half executes, inside the transaction. */
    RoutineCall call();

    /** Whether the emitted fetcher delivers one row or many. */
    Arity arity();

    /** The {@code catch} arm's disposition. */
    ErrorDispatch errors();

    /**
     * Where the post-commit re-read departs from: the chain's first hop, restated as the two
     * facts the re-read actually uses. {@code table} and {@code alias} are what the local
     * declaration and the {@code FROM} spell; {@code capturedPairs} is that hop's column pairing,
     * whose source side the write half captures off the routine's result rows inside the
     * transaction and whose target side is what the re-read filters this table on.
     *
     * <p>The anchor carries no join basis and no filter, and the absence is structural rather
     * than an omission. The re-read departs from this table instead of joining into it: the side
     * a first hop would join from is the routine's own result, which never appears in the
     * post-commit {@code FROM} because re-invoking it would re-execute the write. A join
     * condition or a two-argument filter method there would have no argument to be given.
     */
    record RereadAnchor(CatalogTable table, String alias, List<KeyPair> capturedPairs) {

        public RereadAnchor {
            Objects.requireNonNull(table, "table");
            Objects.requireNonNull(alias, "alias");
            capturedPairs = List.copyOf(capturedPairs);
            if (capturedPairs.isEmpty()) {
                throw new IllegalArgumentException(
                    "a routine-write re-read captures at least one key column; with none the"
                    + " post-commit query has nothing to filter its anchor on and would re-read"
                    + " the whole table");
            }
        }
    }

    /**
     * One forward join of the post-commit re-read, at the grain the re-read joins on: the table
     * and alias to declare, how this hop joins to the one before it, and the optional per-hop
     * filter that lands on the enclosing {@code WHERE}. {@code filter} is null where the path
     * element carried no {@code condition:}.
     *
     * <p>The two catalog-only narrowings this shape exists for are both in its types. {@code table}
     * is a {@link CatalogTable}, so a hop is a table that can be declared and aliased; and
     * {@code on} is a {@link JoinBasis}, which has no lateral arm, so a hop cannot be a routine
     * node correlated against what precedes it. Together they say the family's rule, that the
     * routine appears in no statement after the one that ran it, in a form a renderer inherits
     * rather than re-checks.
     */
    record RereadHop(CatalogTable table, String alias, JoinBasis on, JoinCondition filter) {

        public RereadHop {
            Objects.requireNonNull(table, "table");
            Objects.requireNonNull(alias, "alias");
            Objects.requireNonNull(on, "on");
        }
    }

    /**
     * The direct-return arm: the routine's write, then a post-commit re-read departing from
     * {@link #anchor} keyed by the values captured off the routine's result rows, joining
     * {@link #hops} forward exactly as a read chain does, and projecting the terminus type
     * inline.
     *
     * <p>The re-read's shape is declared here rather than borrowed from the walk's chain carrier,
     * which is what lets the row state it exactly: the anchor is a component, so there is one by
     * construction, and its pairing is a pair list, so there is a key to capture. Both were
     * compact-constructor throws while the row held the wider carrier, and both were second
     * copies of throws the classified leaf already makes. The tail hops are their own grain, so a
     * reader iterating them joins from hop to hop without skipping an anchor that is not one of
     * them.
     */
    record ChainReread(UnitMethodRef unit, FieldCoordinates coordinate, RoutineCall call,
                       RereadAnchor anchor, List<RereadHop> hops,
                       UnitRef terminusProjection, Arity arity,
                       ErrorDispatch errors) implements RoutineWriteCommand {

        public ChainReread {
            Objects.requireNonNull(unit, "unit");
            Objects.requireNonNull(coordinate, "a routine-write row is keyed by its field coordinate");
            Objects.requireNonNull(call, "call");
            Objects.requireNonNull(anchor, "anchor");
            Objects.requireNonNull(terminusProjection, "terminusProjection");
            Objects.requireNonNull(arity, "arity");
            Objects.requireNonNull(errors, "errors");
            hops = List.copyOf(hops);
        }

        /**
         * The alias the projection reads from: the chain's terminus, which is the last tail hop
         * or the anchor itself on a chain that hops no further. Derived rather than carried, so
         * the terminus has one spelling.
         */
        public String terminalAlias() {
            return hops.isEmpty() ? anchor.alias() : hops.getLast().alias();
        }
    }

    /**
     * The payload-carrier arm: the routine's write and the capture of its result keys, nothing
     * else. The captured tuple is projected under the target table's own key fields, because the
     * payload's data field reads its correlation off this record by field identity.
     *
     * <p>{@code capturedPairs} is non-empty (with no captured key the data field's re-read has
     * nothing to correlate on) and name-matched, which is what lets the capture need no join.
     * Its target side is the target table's key by the classifier's pin on the shape; the row
     * carries the pairing and does not re-derive that.
     */
    record CarrierKeys(UnitMethodRef unit, FieldCoordinates coordinate, RoutineCall call,
                       List<KeyPair> capturedPairs, CatalogTable targetTable, Arity arity,
                       ErrorDispatch errors) implements RoutineWriteCommand {

        public CarrierKeys {
            Objects.requireNonNull(unit, "unit");
            Objects.requireNonNull(coordinate, "a routine-write row is keyed by its field coordinate");
            Objects.requireNonNull(call, "call");
            Objects.requireNonNull(targetTable, "targetTable");
            Objects.requireNonNull(arity, "arity");
            Objects.requireNonNull(errors, "errors");
            capturedPairs = List.copyOf(capturedPairs);
            if (capturedPairs.isEmpty()) {
                throw new IllegalArgumentException(
                    "a routine-write carrier captures at least one key column; with none the"
                    + " payload data field's re-read has nothing to correlate on");
            }
        }
    }
}
