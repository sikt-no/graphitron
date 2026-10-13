package no.sikt.graphitron.rewrite.model;

import java.util.List;
import java.util.Optional;

/**
 * Whether a payload-returning DML carrier checks the strings it writes against their columns'
 * declared lengths before the statement runs. Derived, never stored: {@link #of} reads the write
 * plan's {@link OperationMember.Write.Dml#writtenCells()} and the carrier's error channel, so the
 * emitter (which reads {@link Checked}) and the build advisory (which reads {@link Unrouted}) cannot
 * disagree on which cells are bounded or on whether the channel can receive the violation.
 */
public sealed interface LengthCheck permits LengthCheck.Checked, LengthCheck.Unrouted, LengthCheck.None {

    /** The write has length-bounded cells and the channel carries a {@code VALIDATION} handler. */
    record Checked(List<WrittenCell> bounded) implements LengthCheck {
        public Checked {
            bounded = List.copyOf(bounded);
            if (bounded.isEmpty()) {
                throw new IllegalArgumentException("LengthCheck.Checked requires at least one bounded cell");
            }
        }
    }

    /**
     * The write has length-bounded cells and an errors field, but no {@code @error} type on it
     * declares {@code {handler: VALIDATION}}, so an over-long value reaches the database.
     */
    record Unrouted(List<WrittenCell> bounded) implements LengthCheck {
        public Unrouted {
            bounded = List.copyOf(bounded);
            if (bounded.isEmpty()) {
                throw new IllegalArgumentException("LengthCheck.Unrouted requires at least one bounded cell");
            }
        }
    }

    /** No length-bounded cell, or no errors field to route a violation into. */
    record None() implements LengthCheck {
        public static final None INSTANCE = new None();
    }

    /**
     * The one derivation. A carrier with no errors field is {@link None} rather than
     * {@link Unrouted}: giving it somewhere to route means adding an errors field to the payload,
     * a larger schema change than an advisory can ask for.
     */
    static LengthCheck of(OperationMember.Write.Dml write, Optional<ErrorChannel.Mapped> errorChannel) {
        if (errorChannel.isEmpty()) {
            return None.INSTANCE;
        }
        var bounded = write.writtenCells().stream()
            .filter(c -> c.bound() instanceof ColumnWriteBound.Length)
            .toList();
        if (bounded.isEmpty()) {
            return None.INSTANCE;
        }
        return errorChannel.get().hasValidationHandler() ? new Checked(bounded) : new Unrouted(bounded);
    }
}
