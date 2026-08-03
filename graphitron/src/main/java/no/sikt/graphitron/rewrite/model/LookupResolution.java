package no.sikt.graphitron.rewrite.model;

import java.util.Objects;

/**
 * The resolved lookup trigger a table-target coordinate carries: {@link None} when the
 * coordinate's argument surface resolved no {@code @lookupKey} application, {@link Keyed} when
 * it resolved a positional key correspondence. Declared total on the table-target leaves beside
 * {@code filters()} / {@code orderBy()} / {@code pagination()}, so lookup-ness is a payload
 * axis over the surviving fetch leaves rather than a leaf identity, and the operation member
 * production ({@link OperationMembers}) gates the lookup member on the {@link Keyed} arm the
 * way the paginate member gates on the carried window.
 *
 * <p>{@link Keyed} always carries a usable mapping: {@link LookupMapping.ColumnMapping}'s
 * constructor rejects an empty arg list, and the classifier rejects "{@code @lookupKey}
 * declared but no argument resolved to a lookup column" before construction, so the vacuous
 * intermediate the resolver used to hand back is unrepresentable here. A future
 * "rooted at parent via correlated subquery" lookup shape lands as a {@link LookupMapping}
 * sibling permit and every {@link Keyed} reader's switch goes non-total at compile time.
 */
public sealed interface LookupResolution {

    /** The coordinate's argument surface resolved no {@code @lookupKey} application. */
    record None() implements LookupResolution {
        public static final None INSTANCE = new None();
    }

    /** The resolved positional {@code @lookupKey} correspondence keying the coordinate's select. */
    record Keyed(LookupMapping mapping) implements LookupResolution {
        public Keyed {
            Objects.requireNonNull(mapping, "mapping");
        }
    }
}
