package no.sikt.graphitron.record.validation;

import no.sikt.graphitron.record.ValidationError;
import no.sikt.graphitron.record.type.GraphitronType;
import no.sikt.graphitron.record.type.GraphitronType.UnionType;
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

class UnionTypeValidationTest {

    enum Case implements TypeValidatorCase {

        NO_PARTICIPANTS("union type with no member types — valid",
            new UnionType("SearchResult", null, List.of()),
            List.of()),

        ALL_BOUND("all member types are table-bound — valid",
            new UnionType("SearchResult", null, List.of(
                new BoundParticipant("Film", new ResolvedTable("film", "FILM", FILM)),
                new BoundParticipant("Category", new UnresolvedTable("category"))
            )),
            List.of()),

        ONE_UNBOUND("one member type is not table-bound — error",
            new UnionType("SearchResult", null, List.of(
                new BoundParticipant("Film", new ResolvedTable("film", "FILM", FILM)),
                new UnboundParticipant("Description")
            )),
            List.of("Type 'SearchResult': implementing type 'Description' is not table-bound (missing @table directive)")),

        ALL_UNBOUND("all member types are not table-bound — one error per type",
            new UnionType("SearchResult", null, List.of(
                new UnboundParticipant("Film"),
                new UnboundParticipant("Category")
            )),
            List.of(
                "Type 'SearchResult': implementing type 'Film' is not table-bound (missing @table directive)",
                "Type 'SearchResult': implementing type 'Category' is not table-bound (missing @table directive)"
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
    void unionTypeValidation(Case tc) {
        assertThat(validate(tc.type()))
            .extracting(ValidationError::message)
            .containsExactlyInAnyOrderElementsOf(tc.errors());
    }
}
