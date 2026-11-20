package no.sikt.graphitron.plan;

import no.sikt.graphitron.command.Contribution;
import no.sikt.graphitron.command.ProjectionCommand;
import no.sikt.graphitron.command.SelectTerm;
import no.sikt.graphitron.emit.FrameQuery;
import no.sikt.graphitron.emit.VoucherBindingQuery;
import no.sikt.graphitron.model.test.FactGraphs;
import no.sikt.graphitron.rewrite.TestSchemaHelper;
import no.sikt.graphitron.rewrite.test.tier.PipelineTier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.util.List;

import static no.sikt.graphitron.common.configuration.TestConfiguration.DEFAULT_OUTPUT_PACKAGE;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The key a {@code @service} under an object batches on reaches the projection from the store's
 * frames, into the unit selecting its parent row. A nesting type is projected once per table-bound
 * type it is reached from, so each of those units takes the key of its own occurrence's parent and
 * no other's. Read over the {@code defects/service-several-occurrences} fact document, whose
 * FilmStats nests under Film and under FilmAlias.
 */
@PipelineTier
class ServiceKeyProjectionTest {

    @RegisterExtension
    static final FactGraphs.Reader FACTS = FactGraphs.reader();

    /** The fact document's schema, which the walk classifies as the store reads it. */
    private static final String SDL = """
        type FilmStats {
          servedBy: String @service(service: {className: "no.sikt.graphitron.rewrite.test.services.FilmRatingService",
            method: "servedBy"})
        }
        type Film @table(name: "film") {
          title: String
          stats: FilmStats
        }
        type FilmAlias @table(name: "film") {
          title: String
          stats: FilmStats
        }
        type Query {
          films: [Film!]!
          aliases: [FilmAlias!]!
        }
        """;

    private static final class Frames {
        static final List<FrameQuery.Row> STATED = FACTS
            .read("defects/service-several-occurrences", graph -> FrameQuery.read(graph.handle()));
    }

    @Test
    void aNestingTypeTwoParentsShareProjectsEachParentsOwnKey() {
        var schema = TestSchemaHelper.buildSchema(SDL);
        var conditions = ConditionCommands.produce(schema, DEFAULT_OUTPUT_PACKAGE);
        var relation = ProjectionCommands.produce(schema, conditions, DEFAULT_OUTPUT_PACKAGE,
            VoucherBindingQuery.Rows.empty(), Frames.STATED);

        assertThat(keyProjections(relation, "FilmFilmStats"))
            .as("Film's unit takes the key of Film.stats.servedBy only")
            .containsExactly(List.of("film_id"));
        assertThat(keyProjections(relation, "FilmAliasFilmStats"))
            .as("FilmAlias's unit takes the key of FilmAlias.stats.servedBy only")
            .containsExactly(List.of("film_id"));
    }

    /** The columns of each projection under servedBy in the nested unit named {@code unit}. */
    private static List<List<String>> keyProjections(ProjectionRelation relation, String unit) {
        return relation.rows().stream()
            .filter(row -> row instanceof ProjectionCommand.NestedUnit && row.unit().simpleName().equals(unit))
            .flatMap(row -> row.contributions().stream())
            .filter(c -> c instanceof Contribution.Project p && p.field().equals("servedBy"))
            .map(c -> ((Contribution.Project) c).terms().stream()
                .map(term -> ((SelectTerm.Column) term).column().sqlName())
                .toList())
            .toList();
    }
}
