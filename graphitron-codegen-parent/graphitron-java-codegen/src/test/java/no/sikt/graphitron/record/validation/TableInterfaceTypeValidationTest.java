package no.sikt.graphitron.record.validation;

import no.sikt.graphitron.record.ValidationError;
import no.sikt.graphitron.record.type.GraphitronType;
import no.sikt.graphitron.record.type.GraphitronType.TableInterfaceType;
import no.sikt.graphitron.record.type.ParticipantRef.BoundParticipant;
import no.sikt.graphitron.record.type.ParticipantRef.UnboundParticipant;
import no.sikt.graphitron.record.type.TableRef.ResolvedTable;
import no.sikt.graphitron.record.type.TableRef.UnresolvedTable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.List;

import static no.sikt.graphitron.jooq.generated.testdata.public_.Tables.FILM;
import static no.sikt.graphitron.record.validation.FieldValidationTestHelper.validate;
import static org.assertj.core.api.Assertions.assertThat;

class TableInterfaceTypeValidationTest {

    enum Case implements TypeValidatorCase {

        RESOLVED("table name resolved — no implementing types",
            new TableInterfaceType("FilmStatus", null, "status_type", new ResolvedTable("film_status", "FILM_STATUS", FILM), List.of()),
            List.of()),

        UNRESOLVED_TABLE("table name could not be matched to a jOOQ table in the catalog",
            new TableInterfaceType("FilmStatus", null, "status_type", new UnresolvedTable("film_status"), List.of()),
            List.of("Type 'FilmStatus': table 'film_status' could not be resolved in the jOOQ catalog")),

        RESOLVED_WITH_BOUND_PARTICIPANTS("resolved table with table-bound implementing types — valid",
            new TableInterfaceType("FilmStatus", null, "status_type",
                new ResolvedTable("film_status", "FILM_STATUS", FILM),
                List.of(
                    new BoundParticipant("NewFilm", new ResolvedTable("film", "FILM", FILM)),
                    new BoundParticipant("OldFilm", new ResolvedTable("film", "FILM", FILM))
                )),
            List.of()),

        UNBOUND_PARTICIPANT("one implementing type is not table-bound — error",
            new TableInterfaceType("FilmStatus", null, "status_type",
                new ResolvedTable("film_status", "FILM_STATUS", FILM),
                List.of(
                    new BoundParticipant("NewFilm", new ResolvedTable("film", "FILM", FILM)),
                    new UnboundParticipant("FilmDescription")
                )),
            List.of("Type 'FilmStatus': implementing type 'FilmDescription' is not table-bound (missing @table directive)")),

        UNRESOLVED_TABLE_AND_UNBOUND_PARTICIPANT("unresolved table and unbound implementing type — two errors",
            new TableInterfaceType("FilmStatus", null, "status_type",
                new UnresolvedTable("film_status"),
                List.of(new UnboundParticipant("FilmDescription"))),
            List.of(
                "Type 'FilmStatus': table 'film_status' could not be resolved in the jOOQ catalog",
                "Type 'FilmStatus': implementing type 'FilmDescription' is not table-bound (missing @table directive)"
            ));

        private final String description;
        private final GraphitronType type;
        private final List<String> errors;

        Case(String description, GraphitronType type, List<String> errors) {
            this.description = description;
            this.type = type;
            this.errors = errors;
        }

        @Override public GraphitronType type() { return type; }
        @Override public List<String> errors() { return errors; }
        @Override public String toString() { return description; }
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Case.class)
    void tableInterfaceTypeValidation(Case tc) {
        assertThat(validate(tc.type()))
            .extracting(ValidationError::message)
            .containsExactlyInAnyOrderElementsOf(tc.errors());
    }
}
