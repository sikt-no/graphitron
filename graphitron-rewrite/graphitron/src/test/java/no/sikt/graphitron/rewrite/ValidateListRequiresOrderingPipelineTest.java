package no.sikt.graphitron.rewrite;

import no.sikt.graphitron.rewrite.test.tier.PipelineTier;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end coverage for R39: the "list fields require deterministic ordering" check fires
 * through the full SDL → classified model → assembled {@code GraphitronSchema} → validator path,
 * not only when fed a hand-constructed fixture. Pins both the classifier/validator wiring and the
 * error-message contract.
 *
 * <p>Uses {@code film_list}, a Sakila-fixture no-PK table that produces
 * {@code OrderBySpec.None} from {@code OrderByResolver.resolveDefaultOrderSpec} when no
 * {@code @defaultOrder}/{@code @orderBy} is declared.
 */
@PipelineTier
class ValidateListRequiresOrderingPipelineTest {

    @Test
    void listFieldOnNoPkTable_withoutOrdering_rejected() {
        var errors = validate("""
            type FilmListEntry @table(name: "film_list") { title: String }
            type Query { entries: [FilmListEntry!]! }
            """);

        assertThat(messages(errors))
            .anyMatch(m -> m.equals(
                "Field 'Query.entries': list fields must have a deterministic order. "
                    + "Add a primary key to the target table, or use @defaultOrder or @orderBy."));
    }

    @Test
    void listFieldOnNoPkTable_withDefaultOrder_admitted() {
        var errors = validate("""
            type FilmListEntry @table(name: "film_list") { title: String }
            type Query {
                entries: [FilmListEntry!]! @defaultOrder(fields: [{name: "title"}])
            }
            """);

        assertThat(messages(errors))
            .as("@defaultOrder resolves to OrderBySpec.Fixed and clears the list-ordering check")
            .noneMatch(m -> m.contains("list fields must have a deterministic order"));
    }

    private static List<ValidationError> validate(String sdl) {
        var schema = TestSchemaHelper.buildSchema(sdl);
        return new GraphitronSchemaValidator().validate(schema);
    }

    private static List<String> messages(List<ValidationError> errors) {
        return errors.stream().map(ValidationError::message).toList();
    }
}
