package no.sikt.graphitron.rewrite.validation;

import no.sikt.graphitron.rewrite.ValidationError;
import no.sikt.graphitron.rewrite.type.GraphitronType;
import no.sikt.graphitron.rewrite.type.KeyColumnRef;
import no.sikt.graphitron.rewrite.type.NodeRef.NodeDirective;
import no.sikt.graphitron.rewrite.type.NodeRef.NoNode;
import no.sikt.graphitron.rewrite.type.KeyColumnRef.ResolvedKeyColumn;
import no.sikt.graphitron.rewrite.type.TableRef.ResolvedTable;
import no.sikt.graphitron.rewrite.type.GraphitronType.TableType;
import no.sikt.graphitron.rewrite.type.KeyColumnRef.UnresolvedKeyColumn;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.List;

import static no.sikt.graphitron.jooq.generated.testdata.public_.Tables.FILM;
import static no.sikt.graphitron.rewrite.validation.FieldValidationTestHelper.validate;
import static org.assertj.core.api.Assertions.assertThat;

class NodeTypeValidationTest {

    private static final ResolvedTable RESOLVED_FILM = new ResolvedTable("film", "FILM", FILM);

    enum Case implements TypeValidatorCase {

        NO_NODE("no @node directive — NoNode step, no errors",
            new TableType("Film", null, RESOLVED_FILM, new NoNode()),
            List.of()),

        NODE_NO_KEY_COLUMNS("@node with no keyColumns argument — empty list, no errors",
            new TableType("Film", null, RESOLVED_FILM,
                new NodeDirective(null, List.of())),
            List.of()),

        NODE_WITH_TYPE_ID("@node with typeId and no keyColumns — no errors",
            new TableType("Film", null, RESOLVED_FILM,
                new NodeDirective("film", List.of())),
            List.of()),

        NODE_WITH_RESOLVED_KEY_COLUMN("@node with a key column resolved in the jOOQ table — no errors",
            new TableType("Film", null, RESOLVED_FILM,
                new NodeDirective(null, List.of(new ResolvedKeyColumn("film_id", "FILM_ID")))),
            List.of()),

        NODE_WITH_UNRESOLVED_KEY_COLUMN("@node with a key column not found in the jOOQ table — one error",
            new TableType("Film", null, RESOLVED_FILM,
                new NodeDirective(null, List.of(new UnresolvedKeyColumn("bad_col")))),
            List.of("Type 'Film': key column 'bad_col' in @node could not be resolved in the jOOQ table")),

        NODE_WITH_MIXED_KEY_COLUMNS("@node with one resolved and one unresolved key column — one error",
            new TableType("Film", null, RESOLVED_FILM,
                new NodeDirective(null, List.of(
                    new ResolvedKeyColumn("film_id", "FILM_ID"),
                    new UnresolvedKeyColumn("bad_col")))),
            List.of("Type 'Film': key column 'bad_col' in @node could not be resolved in the jOOQ table")),

        NODE_WITH_MULTIPLE_UNRESOLVED_KEY_COLUMNS("@node with multiple unresolved key columns — one error per column",
            new TableType("Film", null, RESOLVED_FILM,
                new NodeDirective(null, List.of(
                    new UnresolvedKeyColumn("bad_col1"),
                    new UnresolvedKeyColumn("bad_col2")))),
            List.of(
                "Type 'Film': key column 'bad_col1' in @node could not be resolved in the jOOQ table",
                "Type 'Film': key column 'bad_col2' in @node could not be resolved in the jOOQ table"));

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
    void nodeTypeValidation(Case tc) {
        assertThat(validate(tc.type()))
            .extracting(ValidationError::message)
            .containsExactlyInAnyOrderElementsOf(tc.errors());
    }
}
