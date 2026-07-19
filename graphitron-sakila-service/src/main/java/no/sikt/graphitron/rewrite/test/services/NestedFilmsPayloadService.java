package no.sikt.graphitron.rewrite.test.services;

import no.sikt.graphitron.rewrite.test.jooq.tables.records.FilmRecord;

import java.util.List;

/**
 * Compilation-tier fixture (producer #1): a root {@code @service} that hand-rolls two
 * {@link NestedFilmsPayloadHolder.Payload} rows. Each nested-record payload exposes a
 * {@code List<FilmRecord>} accessor the classifier walks reflectively to derive a
 * {@code KeyLift.Accessor} lift ({@code Arity.MANY}).
 *
 * <p>The nested-carrier counterpart of {@link CreateFilmsPayloadService}; its only role is to give
 * {@code FieldBuilder.deriveAccessorRecordParentSource} → {@code GeneratorUtils.buildAccessorKeyMany}
 * a nested backing class to emit, so {@code mvn install -Plocal-db} compiles the generated
 * key-extraction cast ({@code Outer.Nested}, not {@code Outer$Nested}).
 */
public final class NestedFilmsPayloadService {

    private NestedFilmsPayloadService() {}

    /** Returns two nested-record payloads; payload 0 lists films [1, 2], payload 1 lists film [3]. */
    public static List<NestedFilmsPayloadHolder.Payload> nestedRecentlyCreatedFilms() {
        FilmRecord f1 = new FilmRecord();
        f1.setFilmId(1);
        FilmRecord f2 = new FilmRecord();
        f2.setFilmId(2);
        FilmRecord f3 = new FilmRecord();
        f3.setFilmId(3);
        return List.of(
            new NestedFilmsPayloadHolder.Payload(List.of(f1, f2)),
            new NestedFilmsPayloadHolder.Payload(List.of(f3))
        );
    }
}
