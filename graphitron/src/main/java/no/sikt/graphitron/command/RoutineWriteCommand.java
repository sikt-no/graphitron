package no.sikt.graphitron.command;

import graphql.schema.FieldCoordinates;
import no.sikt.graphitron.rewrite.model.JoinConditionRef;
import no.sikt.graphitron.rewrite.model.JoinSlot;
import no.sikt.graphitron.rewrite.model.On;
import no.sikt.graphitron.rewrite.model.TableExpr;
import no.sikt.graphitron.rewrite.model.TableRef;

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
 * <p>Generated class names ride as refs ({@link UnitRef}, {@link UnitMethodRef}) so the renderer
 * derives no name: the method's own address, the terminus projection unit, and the two units the
 * {@link ErrorDispatch} arm calls.
 */
public sealed interface RoutineWriteCommand
        permits RoutineWriteCommand.ChainReread, RoutineWriteCommand.CarrierKeys {

    /** The emitted fetcher entry point: its owning fetchers class and its method name. */
    UnitMethodRef unit();

    /** The relation's key: the mutation field this row emits the entry point for. */
    FieldCoordinates coordinate();

    /** The routine call the write half executes, inside the transaction. */
    TableExpr.RoutineCall call();

    /** Whether the emitted fetcher delivers one row or many. */
    Arity arity();

    /** The {@code catch} arm's disposition. */
    ErrorDispatch errors();

    /**
     * Where the post-commit re-read departs from: the chain's first hop, restated as the two
     * facts the re-read actually uses. {@code table} and {@code alias} are what the local
     * declaration and the {@code FROM} spell; {@code capturedSlots} is that hop's column pairing,
     * whose source side the write half captures off the routine's result rows inside the
     * transaction and whose target side is what the re-read filters this table on.
     *
     * <p>The anchor carries no {@code on} and no filter, and the absence is structural rather
     * than an omission. The re-read departs from this table instead of joining into it: the side
     * a first hop would join from is the routine's own result, which never appears in the
     * post-commit {@code FROM} because re-invoking it would re-execute the write. A join
     * condition or a two-argument filter method there would have no argument to be given.
     */
    record RereadAnchor(TableRef table, String alias, List<JoinSlot.FkSlot> capturedSlots) {

        public RereadAnchor {
            Objects.requireNonNull(table, "table");
            Objects.requireNonNull(alias, "alias");
            capturedSlots = List.copyOf(capturedSlots);
            if (capturedSlots.isEmpty()) {
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
     * <p>{@code table} is a {@link TableRef} and not a {@link TableExpr}, which is the narrowing
     * this shape exists for: a re-read hop is a catalog table, the family's rule being that the
     * routine appears in no statement after the one that ran it. A routine node reaching here
     * would carry {@link On.Lateral}, which the renderer refuses by name.
     */
    record RereadHop(TableRef table, String alias, On on, JoinConditionRef filter) {

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
     * construction, and its pairing is a slot list, so there is a key to capture. Both were
     * compact-constructor throws while the row held the wider carrier, and both were second
     * copies of throws the classified leaf already makes. The tail hops are their own grain, so a
     * reader iterating them joins from hop to hop without skipping an anchor that is not one of
     * them.
     */
    record ChainReread(UnitMethodRef unit, FieldCoordinates coordinate, TableExpr.RoutineCall call,
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
    record CarrierKeys(UnitMethodRef unit, FieldCoordinates coordinate, TableExpr.RoutineCall call,
                       List<JoinSlot.FkSlot> capturedPairs, TableRef targetTable, Arity arity,
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
