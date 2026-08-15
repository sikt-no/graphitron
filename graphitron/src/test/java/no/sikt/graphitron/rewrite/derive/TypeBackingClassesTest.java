package no.sikt.graphitron.rewrite.derive;

import no.sikt.graphitron.rewrite.GraphitronSchema;
import no.sikt.graphitron.rewrite.TestSchemaHelper;
import no.sikt.graphitron.rewrite.test.tier.PipelineTier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The backing grain of the walk's reach, pinned against a walked model rather than against a
 * hand-written map: what {@link TypeBackingClasses} reads off the classification walk, and what it
 * deliberately reads nothing off. The writer's own fidelity (rows equal the value handed to it,
 * partition replaced rather than accreted, cleared per graph) is the walk-reach family's anchor in
 * {@code no.sikt.graphitron.rewrite.capture.FactCaptureAgreementTest}; this is the projection side,
 * which is where a silent emptiness would make every future differential pass vacuously.
 */
@PipelineTier
class TypeBackingClassesTest {

    private static final String STUB = "no.sikt.graphitron.rewrite.TestServiceStub";
    private static final String FILM_RECORD =
        "no.sikt.graphitron.rewrite.test.jooq.tables.records.FilmRecord";
    private static final String DETAILS_DTO = "no.sikt.graphitron.rewrite.TestFilmDetailsDto";

    @Test
    @DisplayName("a producer-grounded type carries the class the walk bound it to")
    void aProducerGroundedTypeNamesItsClass() {
        var schema = build("""
            type FilmDetails { title: String }
            type Details { title: String }
            type Query {
                film: FilmDetails
                    @service(service: {className: "%s", method: "getFilm"})
                details: Details
                    @service(service: {className: "%s", method: "getDetails"})
            }
            """.formatted(STUB, STUB));

        assertThat(TypeBackingClasses.of(schema).byTypeName())
            .containsEntry("FilmDetails", FILM_RECORD)
            .containsEntry("Details", DETAILS_DTO);
    }

    /**
     * The two absences the relation's comment names as another relation's population, asserted on
     * a schema where both are present, so neither is absent for want of a fixture.
     */
    @Test
    @DisplayName("a table-backed type and a root are absent, each being another relation's population")
    void thePopulationsAnotherRelationOwnsAreAbsent() {
        var schema = build("""
            type Film @table(name: "film") { title: String }
            type FilmDetails { title: String }
            type Query {
                allFilms: [Film!]
                film: FilmDetails
                    @service(service: {className: "%s", method: "getFilm"})
            }
            """.formatted(STUB));

        var bound = TypeBackingClasses.of(schema).byTypeName();
        assertThat(bound).containsEntry("FilmDetails", FILM_RECORD);
        assertThat(bound).doesNotContainKeys("Film", "Query", "String");
    }

    /**
     * The recorded behaviour difference: two producers naming different classes make the walk
     * refuse to bind at all, so the shadow is silent exactly where the store-native derivation is
     * expected to carry two rows and a conflict. Pinned here so the difference is a fixture rather
     * than a surprise when the derivation lands.
     */
    @Test
    @DisplayName("a type two producers disagree about carries no row at all")
    void aDisagreedTypeIsAbsentRatherThanFirstWins() {
        var schema = build("""
            type FilmDetails { title: String }
            type Query {
                viaFilm: FilmDetails
                    @service(service: {className: "%s", method: "getFilm"})
                viaLanguage: FilmDetails
                    @service(service: {className: "%s", method: "getLanguage"})
            }
            """.formatted(STUB, STUB));

        assertThat(TypeBackingClasses.of(schema).byTypeName()).doesNotContainKey("FilmDetails");
    }

    /**
     * The containment the family header declines to state as a foreign key: every type the walk
     * bound is a type the walk registered. Asserted against the same walked model both values are
     * projected from, which is the only place it can be checked without a constraint.
     */
    @Test
    @DisplayName("every bound type is a registered type")
    void everyBoundTypeIsRegistered() {
        var schema = build("""
            type Film @table(name: "film") { title: String }
            type FilmDetails { title: String }
            type Query {
                allFilms: [Film!]
                film: FilmDetails
                    @service(service: {className: "%s", method: "getFilm"})
            }
            """.formatted(STUB));

        var reach = WalkReach.of(schema);
        assertThat(reach.backingClasses().byTypeName()).isNotEmpty();
        assertThat(reach.domain().typeNames())
            .containsAll(reach.backingClasses().byTypeName().keySet());
    }

    private static GraphitronSchema build(String sdl) {
        return TestSchemaHelper.buildSchema(sdl);
    }
}
