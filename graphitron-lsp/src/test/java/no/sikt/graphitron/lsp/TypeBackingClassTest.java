package no.sikt.graphitron.lsp;

import no.sikt.graphitron.lsp.facts.TypeBackingClass;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The reader behind every surface that has to name the class standing for an SDL type. The store
 * states each class it can reach and prefers none of them, so what is under test here is the two
 * rules the reader adds: a class a producer of the type's own delivers beats one an accessor hop
 * reached, and a type still answered two ways after that is answered not at all.
 *
 * <p>Each case captures its own graph over the fixture classes, so the rows are a real capture's
 * rather than a shape a hand-built store could have. The classes are scanned, not declared: the
 * closure resolves a producer's return through the classfile's own descriptor, which is the reason
 * a fixture reference spelling a display name would not do.
 */
class TypeBackingClassTest {

    private static final String FIXTURES = "no.sikt.graphitron.lsp.fixtures.";
    private static final String SERVICE = FIXTURES + "R157Service";
    private static final String RECORD = FIXTURES + "R157FilmRecord";
    private static final String CARD = FIXTURES + "FilmCardRecord";

    @TempDir
    Path tmp;

    @Test
    void aProducerGroundsTheTypeItReturns() {
        String sdl = """
            type Query {
                card: FilmCard @service(service: {className: "%s", method: "makeFilmRecord"})
            }
            type FilmCard { title: String }
            """.formatted(SERVICE);

        try (var fixture = capture(sdl)) {
            assertThat(TypeBackingClass.of(fixture.handle(), "FilmCard")).contains(RECORD);
        }
    }

    @Test
    void aGroundingBeatsAHopOntoAnotherClass() {
        // FilmCard is grounded on the card record, whose "detail" component is the pojo, so the
        // detail field carries a hop onto it. Detail is grounded on the film record by a producer
        // of its own, and that is the row to believe: the hop read the parent's member type without
        // ever consulting Detail's own grounding.
        String sdl = """
            type Query {
                card: FilmCard @service(service: {className: "%s", method: "makeFilmCard"})
                detail: Detail @service(service: {className: "%s", method: "makeFilmRecord"})
            }
            type FilmCard { detail: Detail }
            type Detail { title: String }
            """.formatted(SERVICE, SERVICE);

        try (var fixture = capture(sdl)) {
            assertThat(TypeBackingClass.of(fixture.handle(), "FilmCard")).contains(CARD);
            assertThat(TypeBackingClass.of(fixture.handle(), "Detail"))
                .as("the closure carries the hop's class too, and the grounding is what answers")
                .contains(RECORD);
        }
    }

    @Test
    void twoProducersNamingDifferentClassesLeaveNoAnswer() {
        String sdl = """
            type Query {
                asRecord: Contested @service(service: {className: "%s", method: "makeFilmRecord"})
                asPojo: Contested @service(service: {className: "%s", method: "makeFilmPojo"})
            }
            type Contested { title: String }
            """.formatted(SERVICE, SERVICE);

        try (var fixture = capture(sdl)) {
            assertThat(TypeBackingClass.of(fixture.handle(), "Contested"))
                .as("both classes are grounded, so the grounding rule settles nothing")
                .isEmpty();
        }
    }

    @Test
    void aTypeNothingReachesHasNoAnswer() {
        String sdl = """
            type Query { unbacked: Unbacked }
            type Unbacked { title: String }
            """;

        try (var fixture = capture(sdl)) {
            assertThat(TypeBackingClass.of(fixture.handle(), "Unbacked")).isEmpty();
            assertThat(TypeBackingClass.of(fixture.handle(), "NoSuchType")).isEmpty();
        }
    }

    private StoreFixture capture(String sdl) {
        return StoreFixture.of(tmp, sdl, StoreFixture.backingClasses());
    }
}
