package no.sikt.graphitron.rewrite;

import no.sikt.graphitron.common.configuration.TestConfiguration;
import no.sikt.graphitron.model.schema.input.SchemaInput;
import no.sikt.graphitron.rewrite.test.tier.PipelineTier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import no.sikt.graphitron.model.diagnostics.BuildWarning;
import no.sikt.graphitron.model.config.RunContext;
import no.sikt.graphitron.model.diagnostics.ValidationError;
import no.sikt.graphitron.model.diagnostics.ValidationFailedException;

/**
 * The claim behind collapsing the dev loop's two generator entry points into one:
 * {@link GraphQLRewriteGenerator#runPass()} computes everything
 * {@link GraphQLRewriteGenerator#buildOutput()} computes and everything
 * {@link GraphQLRewriteGenerator#generate()} computes, so a dev round needs one pass and not two.
 * Both survivors are driven here, so this is an executable comparison of the projections rather
 * than a comparison against something deleted.
 *
 * <p>Three properties, over two fixtures:
 *
 * <ul>
 *   <li><b>Agreement.</b> On a clean schema the pass's reporting half equals {@code buildOutput()}'s
 *       component by component (catalog, report errors, warnings) and its emitted half names the
 *       same units {@code generate()} emits. This is the gate that fails if the merge drops a
 *       product or computes one differently on the two paths.</li>
 *   <li><b>The rejected round.</b> A validation error is a value on the report rather than a throw,
 *       the catalog and the diagnostics lists are produced anyway (so the editor can autocomplete
 *       its way out of the typo), and nothing is emitted at all.</li>
 *   <li><b>The invariant.</b> A generation is present exactly when the report carries no errors,
 *       asserted on both fixtures so a future arm returning a generation beside errors fails
 *       here.</li>
 * </ul>
 *
 * <p>The build entry points' own verdicts are covered where the verdict is the subject (see
 * {@code ArgmappingProjectionRejectionPipelineTest} and its siblings); what is asserted here is
 * that they still throw the same list the pass reports, which is the claim the merge could break.
 */
@PipelineTier
class DevPassProjectionPipelineTest {

    /**
     * A schema that classifies clean and reaches every emitting family the pass has: a root table
     * read, a connection, a table reference, a node lookup and a DML insert.
     */
    private static final String CLEAN = """
        interface Node { id: ID! }

        type Query {
          film: Film
          films: [Film!]! @asConnection
          node(id: ID!): Node
        }

        type Film implements Node @table(name: "film") @node {
          id: ID! @nodeId
          title: String
          language: Language @reference(path: [{key: "film_language_id_fkey"}])
        }

        type Language @table(name: "language") { name: String }

        input FilmInput { title: String }

        type Mutation {
          createFilm(in: FilmInput!): Film @mutation(typeName: INSERT)
        }
        """;

    /**
     * A schema the validator rejects, over a type that otherwise classifies: the {@code @reference}
     * names no foreign key in the catalog, so {@code Film.languageName} is unclassified. Everything
     * else about the document is fine, which is what makes the catalog worth producing.
     */
    private static final String REJECTED = """
        type Film @table(name: "film") {
          languageName: String @reference(path: [{key: "no_such_fk"}])
        }
        type Query { film: Film }
        """;

    @Test
    void runPass_producesEverythingBuildOutputAndGenerateProduce(@TempDir Path tmp) throws IOException {
        var ctx = contextFor(tmp, CLEAN);

        var generated = new GraphQLRewriteGenerator(ctx).generate();
        var reported = new GraphQLRewriteGenerator(ctx).buildOutput();
        var pass = new GraphQLRewriteGenerator(ctx).runPass();

        assertThat(pass.output().catalog())
            .as("the completion catalog the pass projects is the one buildOutput() projects")
            .isEqualTo(reported.catalog());
        assertThat(pass.output().report().errors())
            .as("no errors on either path, which is what makes the fixture the clean one")
            .isEqualTo(reported.report().errors());
        assertThat(pass.output().report().errors()).isEmpty();
        assertThat(pass.output().walkErrors())
            .as("the rejection-residue loader's input agrees across the two projections")
            .isEqualTo(reported.walkErrors());
        assertThat(pass.output().warnings())
            .extracting(BuildWarning::message)
            .as("the suppression-filtered warning list agrees across the two projections")
            .isEqualTo(reported.warnings().stream().map(BuildWarning::message).toList());

        assertThat(pass.generation())
            .as("a clean round emits, so the generation the compile driver reads is present")
            .isPresent();
        var generation = pass.generation().orElseThrow();
        assertThat(generation.result().emittedUnits().keySet())
            .as("the units the pass writes are the units generate() writes for the same context")
            .isEqualTo(generated.emittedUnits().keySet());
        assertThat(generation.result().emitted())
            .as("and it lands them at the same addresses, the SDL resource included")
            .isEqualTo(generated.emitted());
        assertThat(generation.graph())
            .as("the pass is the only projection that also builds the compile graph")
            .isNotNull();
        assertThat(generation.graph().nodes())
            .extracting(no.sikt.graphitron.command.UnitRef::fqcn)
            .as("projected from the same plan, so it names the units the run rendered")
            .containsExactlyInAnyOrderElementsOf(generation.result().emittedUnits().keySet());
    }

    @Test
    void runPass_rejectedRound_reportsTheErrorsAndEmitsNothing(@TempDir Path tmp) throws IOException {
        var ctx = contextFor(tmp, REJECTED);

        var pass = new GraphQLRewriteGenerator(ctx).runPass();

        assertThat(pass.output().report().errors())
            .extracting(ValidationError::message)
            .as("the verdict is a value on the report, not an exception out of the pass")
            .anyMatch(m -> m.contains("languageName") || m.contains("no_such_fk"));
        assertThat(pass.output().walkErrors())
            .as("the walk's own error stream, which the diagnostics stratum is written from")
            .isNotEmpty();
        assertThat(pass.generation())
            .as("a rejected schema emits nothing, so there is no generation to compile")
            .isEmpty();

        // The editor still gets a catalog: a half-edited buffer should be able to autocomplete its
        // way out of the error that stopped the build.
        assertThat(pass.output().catalog().tables())
            .as("the rejected round still projects the jOOQ tables the editor completes from")
            .isNotEmpty();
        assertThat(pass.output().catalog().types())
            .as("and the scalars beside them")
            .isNotEmpty();

        // Nothing half-emitted: the rejection skips the plan, the renderers, the writer and the
        // orphan sweep as one, rather than running some of them.
        try (Stream<Path> tree = Files.walk(tmp)) {
            assertThat(tree.filter(p -> p.toString().endsWith(".java")).toList())
                .as("a rejected round writes no source at all")
                .isEmpty();
        }
    }

    @Test
    void theBuildEntryPointsStillThrowTheListThePassReports(@TempDir Path tmp) throws IOException {
        var ctx = contextFor(tmp, REJECTED);
        var reportedErrors = new GraphQLRewriteGenerator(ctx).runPass()
            .output().report().errors().stream().map(ValidationError::message).toList();

        assertThatThrownBy(() -> new GraphQLRewriteGenerator(ctx).generate())
            .as("generate() fails the build on the same errors the dev pass merely reports")
            .isInstanceOf(ValidationFailedException.class)
            .satisfies(e -> assertThat(((ValidationFailedException) e).errors())
                .extracting(ValidationError::message)
                .isEqualTo(reportedErrors));
        assertThatThrownBy(() -> new GraphQLRewriteGenerator(ctx).validate())
            .as("and so does validate(), which emits nothing either way")
            .isInstanceOf(ValidationFailedException.class)
            .satisfies(e -> assertThat(((ValidationFailedException) e).errors())
                .extracting(ValidationError::message)
                .isEqualTo(reportedErrors));
    }

    private static RunContext contextFor(Path tmp, String sdl) throws IOException {
        Path schema = tmp.resolve("schema.graphqls");
        Files.writeString(schema, sdl);
        return new RunContext(
            List.of(SchemaInput.file(schema)),
            tmp, "DevPassProjectionPipelineTest",
            tmp.resolve("generated-sources"),
            TestConfiguration.DEFAULT_OUTPUT_PACKAGE,
            TestConfiguration.DEFAULT_JOOQ_PACKAGE);
    }
}
