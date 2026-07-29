package no.sikt.graphitron.command;

import java.util.List;
import java.util.Objects;

/**
 * One selection-gated entry in a projection unit's contribution list. Every arm carries its
 * gating SDL field name as a mandatory component, so an ungated contribution is unrepresentable
 * and the compiler enforces the no-unconditional-columns invariant; anything a mechanism needs
 * regardless of selection belongs to a launcher, never here.
 *
 * <p>Two arms, on one structural axis: whether the terms are built from this unit's own table
 * context ({@link Project}) or decided by another projection unit ({@link Call}). Provenance is
 * not a distinction: a correlation key, a node key and a plain scalar all land as
 * {@link Project} column terms, because nothing downstream needs to know why a column is
 * wanted.
 *
 * <p>The gate is the field, not the result key: result keys are per-request values the client
 * mints, so the emitted switch matches on the field name and iterates the selected occurrences,
 * each occurrence keyed by its result key.
 */
public sealed interface Contribution {

    /** The SDL field whose selection gates this contribution. */
    String field();

    /** Terms this unit builds from its own table context. */
    record Project(String field, List<SelectTerm> terms) implements Contribution {
        public Project {
            requireField(field, "Project");
            terms = List.copyOf(terms);
            if (terms.isEmpty()) {
                throw new IllegalArgumentException(
                    "Project '" + field + "' requires at least one term; a field that lands "
                    + "nothing has no contribution");
            }
        }
    }

    /** Fields another projection unit decides, arriving per {@link CallWrap}. */
    record Call(String field, UnitRef callee, CallWrap wrap) implements Contribution {
        public Call {
            requireField(field, "Call");
            Objects.requireNonNull(callee, "callee");
            Objects.requireNonNull(wrap, "wrap");
        }
    }

    private static void requireField(String field, String arm) {
        if (field == null || field.isBlank()) {
            throw new IllegalArgumentException(arm + ".field must be non-blank: every "
                + "contribution is gated on client selection of a named SDL field");
        }
    }
}
