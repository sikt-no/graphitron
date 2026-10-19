package no.sikt.graphitron.rewrite.test.services;

import no.sikt.graphitron.rewrite.test.jooq.public_.tables.records.FilmRecord;

import java.util.List;

/**
 * Fixture: root {@code @service} that hand-rolls two {@link CreateFilmsPayload} rows for
 * {@code AccessorDerivedSourceTest}'s {@code Many} case. Each payload exposes a
 * {@code List<FilmRecord>} accessor that the classifier walks reflectively to derive an
 * accessor-derived batch key.
 *
 * <p>The service hand-rolls payloads so the only JDBC round-trip in the test is the
 * batched {@code loadMany} lookup of the films listed across all parents. Returned films use
 * deterministic IDs so the test can pin specific rows; the second-hop {@code language} child
 * field exercises the chained DataLoader from a record parent into a real table.
 */
public final class CreateFilmsPayloadService {

    private CreateFilmsPayloadService() {}

    /**
     * Returns two payloads. Payload 0 lists films [1, 2]; payload 1 lists film [3]. The
     * union of accessor-derived keys across the two parents is three element-PK keys
     * ({@code Record1<Integer>}); {@code loader.loadMany} dispatches once with that union and
     * returns one record per key, redistributed back to the parents by the framework.
     */
    public static List<CreateFilmsPayload> recentlyCreatedFilmsBatched() {
        FilmRecord f1 = new FilmRecord();
        f1.setFilmId(1);
        FilmRecord f2 = new FilmRecord();
        f2.setFilmId(2);
        FilmRecord f3 = new FilmRecord();
        f3.setFilmId(3);
        return List.of(
            new CreateFilmsPayload(List.of(f1, f2)),
            new CreateFilmsPayload(List.of(f3))
        );
    }

    /**
     * Fixture: returns two payloads where the second carries a {@code null} {@code films}
     * backing (a to-many relation that was never populated). The first payload lists film [1]; the
     * second's {@code films()} accessor returns {@code null}. The MANY accessor key-extraction
     * ({@code GeneratorUtils.buildAccessorKeyMany}) must skip its for-loop on the null collection so
     * the field renders {@code []} rather than NPEing in the for-each.
     */
    public static List<CreateFilmsPayload> recentlyCreatedFilmsBatchedWithGap() {
        FilmRecord f1 = new FilmRecord();
        f1.setFilmId(1);
        return List.of(
            new CreateFilmsPayload(List.of(f1)),
            new CreateFilmsPayload(null)
        );
    }

    /**
     * Fixture: one payload whose {@code films} accessor lists films [1, 999999, 2], where film
     * 999999 has no row. The {@code loadMany} dispatch answers {@code null} for that key and the
     * field drops it, so the list renders films [1, 2].
     */
    public static List<CreateFilmsPayload> recentlyCreatedFilmsBatchedWithMissingRow() {
        FilmRecord f1 = new FilmRecord();
        f1.setFilmId(1);
        FilmRecord missing = new FilmRecord();
        missing.setFilmId(999999);
        FilmRecord f2 = new FilmRecord();
        f2.setFilmId(2);
        return List.of(new CreateFilmsPayload(List.of(f1, missing, f2)));
    }
}
