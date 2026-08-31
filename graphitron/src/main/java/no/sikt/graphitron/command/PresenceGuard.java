package no.sikt.graphitron.command;

import java.util.List;

/**
 * When an {@link Predicate.Authored} predicate contributes its conjunct: unconditionally, or only
 * when one named input field carried a value on the wire. Producer-computed, the way the generated
 * arm's {@link ColumnTerm#nonNull()} is: the renderer derives the mechanical spelling of the guard
 * and makes no decision about it.
 *
 * <p>The split is not about the author's method, which always fires when it is called. It is about
 * who owns the row-dropping structure around the call. A same-table {@code @condition} has none:
 * the method's return value reaches the WHERE clause with nothing of ours in between, so the
 * author's convention of mapping an absent value to {@code noCondition()} fully controls the
 * semantics and the predicate is {@link Always}. An FK-target {@code @condition} is wrapped in a
 * correlated {@code EXISTS} that graphitron mints, which is a semi-join: applied on behalf of a
 * value nobody supplied, it silently narrows the query to the rows that have the relation at all,
 * and the author cannot opt out from inside their method. That wrapper obeys the rule every
 * implicit conjunct already follows, an absent value contributes no conjunct, which is
 * {@link FieldPresent}.
 */
public sealed interface PresenceGuard {

    /** The predicate always contributes its conjunct. */
    record Always() implements PresenceGuard {
        private static final Always INSTANCE = new Always();
    }

    /**
     * The predicate contributes its conjunct only when one named input field carried a value: a
     * non-null wire value, and for a {@code list} field a non-empty one. An explicit {@code null}
     * and an empty list both mean no value, exactly what the implicit arm gives both shapes. When
     * the field is absent the authored method is not called at all: there is no value for it to
     * map, and the one observable it could otherwise produce is the row-dropping wrapper.
     *
     * <p>The address is the field's own wire address, narrowed off the model's
     * {@code WireAddress} at production: {@code outerArgName} is the top-level argument, and
     * {@code path} the non-empty key descent from it down to the field, which is the args-map
     * traversal the binding locals take stopped one step short of decoding.
     *
     * <p>The grain is the field, never the callee's parameter list. A method that declares a
     * parameter for the value and one that ignores it describe the same query semantics, and a
     * signature-derived guard would give them opposite row sets; the field-grain read also leaves
     * a value-less signature perfectly guardable, so no generate-time rejection is owed for one.
     */
    record FieldPresent(String outerArgName, List<String> path, boolean list) implements PresenceGuard {
        public FieldPresent {
            if (outerArgName == null || outerArgName.isBlank()) {
                throw new IllegalArgumentException("a field-presence guard names the argument its read roots at");
            }
            path = List.copyOf(path);
            if (path.isEmpty()) {
                throw new IllegalArgumentException(
                    "a field-presence guard under '" + outerArgName + "' carries at least one path segment; "
                    + "the argument itself is not the filter field");
            }
        }
    }

    /** The unguarded predicate; see {@link Always}. */
    static PresenceGuard always() {
        return Always.INSTANCE;
    }
}
