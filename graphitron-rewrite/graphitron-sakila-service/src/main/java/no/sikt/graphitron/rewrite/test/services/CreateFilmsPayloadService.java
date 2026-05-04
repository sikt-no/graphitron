package no.sikt.graphitron.rewrite.test.services;

import no.sikt.graphitron.rewrite.test.jooq.tables.records.FilmRecord;

import java.util.List;

/**
 * R60 fixture: root {@code @service} that hand-rolls two {@link CreateFilmsPayload} rows for
 * {@code AccessorDerivedBatchKeyTest}'s {@code Many} case. Each payload exposes a
 * {@code List<FilmRecord>} accessor that the classifier walks reflectively to derive
 * {@code BatchKey.AccessorKeyedMany}.
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
}
