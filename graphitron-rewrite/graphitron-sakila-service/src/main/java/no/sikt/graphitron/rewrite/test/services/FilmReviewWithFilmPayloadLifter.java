package no.sikt.graphitron.rewrite.test.services;

import org.jooq.Row1;
import org.jooq.impl.DSL;

/**
 * R268 fixture: lifter helper for {@link FilmReviewWithFilmPayload}, used by the {@code @sourceRow}
 * directive on {@code FilmReviewWithFilmPayload.film}. Same leaf-PK shape as
 * {@link CreateFilmPayloadLifter#liftLanguageId}: the {@code Row1<Integer>} matches the leaf table
 * {@code film}'s primary key ({@code FILM_ID}) directly, so the resolver derives the parent-side
 * tuple from {@code TableRef.primaryKeyColumns()} without {@code @reference}.
 *
 * <p>Invoked once per parent payload row; the {@code Row1<Integer>} is fed into the rows-method's
 * {@code film_id IN (...)} batch. Under the R268 arm-switch the generated film fetcher narrows
 * {@code Outcome.Success} and reads {@code filmId} off {@code success.value()} before invoking this
 * lifter, so on the {@code ErrorList} arm the lifter is never called.
 */
public final class FilmReviewWithFilmPayloadLifter {

    private FilmReviewWithFilmPayloadLifter() {}

    public static Row1<Integer> liftFilmId(FilmReviewWithFilmPayload parent) {
        return DSL.row(parent.filmId());
    }
}
