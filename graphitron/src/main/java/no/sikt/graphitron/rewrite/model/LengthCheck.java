package no.sikt.graphitron.rewrite.model;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Whether a DML carrier checks the strings it writes against their columns' declared lengths
 * before the statement runs, and where a violation goes. Derived, never stored: {@link #of} reads
 * the write plan's {@link OperationMember.Write.Dml#writtenCells()} and the carrier's error channel, so the
 * emitter (which reads {@link Checked}) and the build advisory (which reads {@link Unrouted}) cannot
 * disagree on which cells are bounded or on whether the channel can receive the violation.
 */
public sealed interface LengthCheck permits LengthCheck.Checked, LengthCheck.Unrouted, LengthCheck.None {

    /**
     * The write has length-bounded cells and checks them before the statement; {@link #sink()}
     * says where a violation goes.
     */
    record Checked(List<WrittenCell> bounded, Sink sink) implements LengthCheck {
        public Checked {
            bounded = List.copyOf(bounded);
            if (bounded.isEmpty()) {
                throw new IllegalArgumentException("LengthCheck.Checked requires at least one bounded cell");
            }
            Objects.requireNonNull(sink, "sink");
        }
    }

    /** Where a {@link Checked} write's violations go. */
    enum Sink {
        /** The payload's errors field has a {@code VALIDATION} member: each violation is an entry in the union. */
        CHANNEL,
        /** The payload has no errors field, or there is no payload: the violations are the field's top-level errors. */
        TOP_LEVEL
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

    /** No length-bounded cell. */
    record None() implements LengthCheck {
        public static final None INSTANCE = new None();
    }

    /**
     * The one derivation. A carrier with no errors field is always {@link Checked} with
     * {@link Sink#TOP_LEVEL}: there is no union whose routing a check could change, and without
     * the check the over-long value already fails as a top-level error, only a redacted one. A
     * carrier with an errors field is checked only when the channel carries a {@code VALIDATION}
     * member; otherwise a {@code DATABASE} handler there may already be catching the overflow,
     * so it stays {@link Unrouted} and the build advises.
     */
    static LengthCheck of(OperationMember.Write.Dml write, Optional<ErrorChannel.Mapped> errorChannel) {
        var bounded = write.writtenCells().stream()
            .filter(c -> c.bound() instanceof ColumnWriteBound.Length)
            .toList();
        if (bounded.isEmpty()) {
            return None.INSTANCE;
        }
        if (errorChannel.isEmpty()) {
            return new Checked(bounded, Sink.TOP_LEVEL);
        }
        return errorChannel.get().hasValidationHandler()
            ? new Checked(bounded, Sink.CHANNEL)
            : new Unrouted(bounded);
    }
}
