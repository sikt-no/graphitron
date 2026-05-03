package no.sikt.graphitron.rewrite.test.services;

import org.jooq.Row1;
import org.jooq.impl.DSL;

/**
 * R1 Phase 2f fixture: lifter helper for {@link CreateFilmPayload} used by the
 * {@code @batchKeyLifter} directive on {@code CreateFilmPayload.language}.
 *
 * <p>The lifter is invoked once per parent row; the {@code Row1<Integer>} return value is fed
 * into the rows-method's {@code language_id IN (...)} batch. The directive declares
 * {@code targetColumns: ["language_id"]}, matching the single Row1 column-class to the
 * {@code language.language_id} jOOQ column class.
 */
public final class CreateFilmPayloadLifter {

    private CreateFilmPayloadLifter() {}

    /**
     * Lifts the parent payload's {@code languageId} into a {@code Row1<Integer>}. Hand-written
     * fixture target for the rewrite generator's {@code @batchKeyLifter} reflection: the resolver
     * verifies (1) the parameter is assignable from the parent's backing class
     * ({@link CreateFilmPayload}), (2) the return type is {@code org.jooq.Row1..Row22}, and
     * (3) each Row's type argument matches the corresponding target column's column class.
     */
    public static Row1<Integer> liftLanguageId(CreateFilmPayload parent) {
        return DSL.row(parent.languageId());
    }
}
