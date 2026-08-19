package no.sikt.graphitron.command;

import graphql.schema.FieldCoordinates;
import no.sikt.graphitron.rewrite.model.JoinSlot;
import no.sikt.graphitron.rewrite.model.JoinStep;
import no.sikt.graphitron.rewrite.model.On;
import no.sikt.graphitron.rewrite.model.RoutineChain;
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
     * The direct-return arm: the routine's write, then a post-commit re-read anchored on the
     * chain's first hop and keyed by the values captured off the routine's result rows,
     * projecting the terminus type inline.
     *
     * <p>{@code hops} is the {@code @reference}-contributed tail of {@link #chain}, restated as
     * an accessor rather than a slot. The two pins the re-read needs are stated here rather than
     * left to the classifier that produced the row: there is at least one hop, so an anchor
     * exists, and hop 0 joins by column pairs, so there is a key to capture. A renderer reading
     * this row narrows on that authority instead of on faith.
     */
    record ChainReread(UnitMethodRef unit, FieldCoordinates coordinate, RoutineChain chain,
                       UnitRef terminusProjection, Arity arity,
                       ErrorDispatch errors) implements RoutineWriteCommand {

        public ChainReread {
            Objects.requireNonNull(unit, "unit");
            Objects.requireNonNull(coordinate, "a routine-write row is keyed by its field coordinate");
            Objects.requireNonNull(chain, "chain");
            Objects.requireNonNull(terminusProjection, "terminusProjection");
            Objects.requireNonNull(arity, "arity");
            Objects.requireNonNull(errors, "errors");
            if (chain.hops().isEmpty()) {
                throw new IllegalArgumentException(
                    "a direct-return routine write re-reads through at least one hop; with no hop"
                    + " there is no post-commit table to anchor on, and the hop-less shape is the"
                    + " carrier arm's");
            }
            if (!(((JoinStep.Hop) chain.hops().get(0)).on() instanceof On.ColumnPairs)) {
                throw new IllegalArgumentException(
                    "a direct-return routine write captures hop 0's column pairs; a hop 0 joining"
                    + " any other way leaves the post-commit re-read no key to filter on");
            }
        }

        @Override public TableExpr.RoutineCall call() {
            return chain.start();
        }

        /** The chain's {@code @reference}-contributed steps, in authored order. */
        public List<JoinStep> hops() {
            return chain.hops();
        }

        /**
         * Hop 0's column pairing: the source side is captured off the routine's result rows
         * inside the transaction, the target side is what the re-read filters hop 0's table on.
         * Derived from the chain rather than carried beside it, so the pairing has one spelling.
         */
        public List<JoinSlot.FkSlot> capturedSlots() {
            return ((On.ColumnPairs) ((JoinStep.Hop) chain.hops().get(0)).on()).slots();
        }

        /** The alias the re-read anchors on: hop 0's. */
        public String anchorAlias() {
            return ((JoinStep.Hop) chain.hops().get(0)).alias();
        }

        /** The alias the projection reads from: the last hop's, the chain's terminus. */
        public String terminalAlias() {
            return ((JoinStep.Hop) chain.hops().getLast()).alias();
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
