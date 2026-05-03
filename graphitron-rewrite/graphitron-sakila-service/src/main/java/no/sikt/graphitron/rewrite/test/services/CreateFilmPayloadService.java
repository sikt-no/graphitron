package no.sikt.graphitron.rewrite.test.services;

import java.util.List;

/**
 * R1 Phase 2f fixture: root {@code @service} that produces three {@link CreateFilmPayload} rows
 * for {@code MutationPayloadLifterTest}. Two payloads share {@code languageId=1}; the third
 * has {@code languageId=2}. The execution test asserts that the framework's lifter-driven
 * DataLoader dispatches once with {@code Set} of two distinct keys (1 and 2), not three.
 *
 * <p>The method takes no DSLContext: payload construction is hand-rolled, so the only JDBC
 * round-trip in the test is the batched language lookup. Without this guarantee, the
 * {@code QUERY_COUNT == 1} assertion would conflate root-fetch and child-fetch traffic.
 */
public final class CreateFilmPayloadService {

    private CreateFilmPayloadService() {}

    /**
     * Returns three deterministic payloads. Order matters: positions 0 and 2 share the same
     * {@code languageId}, exercising the DataLoader's key-deduplication.
     */
    public static List<CreateFilmPayload> recentlyCreatedFilms() {
        return List.of(
            new CreateFilmPayload(1),
            new CreateFilmPayload(2),
            new CreateFilmPayload(1)
        );
    }
}
