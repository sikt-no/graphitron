package no.sikt.graphitron.rewrite.validation;

import no.sikt.graphitron.rewrite.ValidationError;
import no.sikt.graphitron.rewrite.type.GraphitronType;
import no.sikt.graphitron.rewrite.type.GraphitronType.InterfaceType;
import no.sikt.graphitron.rewrite.type.ParticipantRef.BoundParticipant;
import no.sikt.graphitron.rewrite.type.ParticipantRef.UnboundParticipant;
import no.sikt.graphitron.rewrite.type.TableRef.ResolvedTable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.List;

import static no.sikt.graphitron.jooq.generated.testdata.public_.Tables.ACTOR;
import static no.sikt.graphitron.jooq.generated.testdata.public_.Tables.FILM;
import static no.sikt.graphitron.rewrite.validation.FieldValidationTestHelper.validate;
import static org.assertj.core.api.Assertions.assertThat;

class InterfaceTypeValidationTest {

    enum Case implements TypeValidatorCase {

        NO_PARTICIPANTS("interface type with no implementing types — valid",
            new InterfaceType("Node", null, List.of()),
            List.of()),

        ALL_BOUND("all implementing types are table-bound — valid",
            new InterfaceType("Media", null, List.of(
                new BoundParticipant("Film", new ResolvedTable("film", "FILM", FILM), null),
                new BoundParticipant("Actor", new ResolvedTable("actor", "ACTOR", ACTOR), null)
            )),
            List.of()),

        ONE_UNBOUND("one implementing type is not table-bound — error",
            new InterfaceType("Media", null, List.of(
                new BoundParticipant("Film", new ResolvedTable("film", "FILM", FILM), null),
                new UnboundParticipant("Description")
            )),
            List.of("Type 'Media': implementing type 'Description' is not table-bound (missing @table directive)")),

        ALL_UNBOUND("all implementing types are not table-bound — one error per type",
            new InterfaceType("Media", null, List.of(
                new UnboundParticipant("Film"),
                new UnboundParticipant("Book")
            )),
            List.of(
                "Type 'Media': implementing type 'Film' is not table-bound (missing @table directive)",
                "Type 'Media': implementing type 'Book' is not table-bound (missing @table directive)"
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
    void interfaceTypeValidation(Case tc) {
        assertThat(validate(tc.type()))
            .extracting(ValidationError::message)
            .containsExactlyInAnyOrderElementsOf(tc.errors());
    }
}
