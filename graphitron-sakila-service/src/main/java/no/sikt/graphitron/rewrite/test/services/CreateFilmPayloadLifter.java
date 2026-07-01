package no.sikt.graphitron.rewrite.test.services;

import org.jooq.Row1;
import org.jooq.impl.DSL;

/**
 * R110 fixture: lifter helper for {@link CreateFilmPayload} used by the {@code @sourceRow}
 * directive on {@code CreateFilmPayload.language}. Demonstrates the leaf-PK shape (no
 * {@code @reference}): the lifter's {@code Row1<Integer>} matches the leaf table
 * {@code language}'s primary key directly, so the resolver derives the parent-side tuple
 * from {@code TableRef.primaryKeyColumns()} without invoking {@code BuildContext.parsePath}.
 *
 * <p>The lifter is invoked once per parent row; the {@code Row1<Integer>} return value is fed
 * into the rows-method's {@code language_id IN (...)} batch.
 */
public final class CreateFilmPayloadLifter {

    private CreateFilmPayloadLifter() {}

    /**
     * Lifts the parent payload's {@code languageId} into a {@code Row1<Integer>}. Hand-written
     * fixture target for the rewrite generator's {@code @sourceRow} reflection: the resolver
     * verifies (1) the parameter is assignable from the parent's backing class
     * ({@link CreateFilmPayload}), (2) the return type is {@code org.jooq.Row1..Row22}, and
     * (3) each Row's type argument matches the corresponding target column's column class.
     */
    public static Row1<Integer> liftLanguageId(CreateFilmPayload parent) {
        return DSL.row(parent.languageId());
    }
}
