package no.sikt.graphitron.rewrite;

import graphql.schema.GraphQLFieldDefinition;
import graphql.schema.GraphQLObjectType;
import graphql.schema.GraphQLTypeReference;
import graphql.schema.idl.SchemaParser;
import no.sikt.graphitron.rewrite.model.GraphitronType;
import no.sikt.graphitron.model.schema.SchemaLoader;
import no.sikt.graphitron.rewrite.test.tier.PipelineTier;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;

import static no.sikt.graphitron.common.configuration.TestConfiguration.DEFAULT_OUTPUT_PACKAGE;
import static org.assertj.core.api.Assertions.assertThat;
import no.sikt.graphitron.model.config.RunContext;

/**
 * Coverage for {@code GraphitronSchemaBuilder.rejectUnregisteredScalarReferences}, the
 * reference-closure guard over the scalar axis: every scalar the emitted schema names must have a
 * registration row, or the generated {@code GraphitronSchema.build()} names it through a type
 * reference with nothing registering it and consumer assembly fails.
 *
 * <p>Three arms. The quiet arm is the reported bug's own schema shape, which the demand sweep in
 * {@link ConnectionPromoter} now covers, so the guard finds nothing left to say. The suppression arm
 * is an author-misconfigured scalar, whose demotion diagnostic is the richer report and must not be
 * doubled here. The firing arm has no SDL fixture once the sweep is in place, so it drives the guard
 * over a hand-built model row.
 */
@PipelineTier
class ScalarReferenceClosurePipelineTest {

    /**
     * The reported failure's shape: the only built-in scalar the emitted schema needs arrives
     * through the pagination surface {@code @asConnection} synthesises, and the SDL text names it
     * nowhere.
     */
    private static final String BUILT_IN_FREE_CONNECTION_CARRIER = """
        type Film @table(name: "film") { title: String }
        type Query { films: [Film!]! @asConnection @defaultOrder(primaryKey: true) }
        """;

    @Test
    void builtInFreeConnectionCarrier_leavesTheClosureGuardNothingToReport() {
        var schema = TestSchemaHelper.buildSchema(BUILT_IN_FREE_CONNECTION_CARRIER);

        assertThat(schema.diagnostics())
            .as("the demand sweep registers the minted surface's scalars, so the guard stays quiet")
            .noneMatch(e -> e.message().contains("not found in schema"));
        assertThat(schema.types().get("Int")).isInstanceOf(GraphitronType.ScalarType.class);
    }

    @Test
    void misconfiguredScalar_isReportedOnceByTheClassifier_notAgainByTheGuard() {
        var schema = TestSchemaHelper.buildSchema("""
            scalar Money @scalarType(scalar: "does.not.exist.Class.FIELD")
            type Film @table(name: "film") { rentalRate: Money }
            type Query { films: [Film!]! @asConnection @defaultOrder(primaryKey: true) }
            """);

        // The scalar demoted to UnclassifiedType, so it has no ScalarType row and the emitted
        // schema does reference it. That demotion carries the author-actionable report (the
        // validator's unclassified-type pass turns it into the build error); the guard suppresses
        // the name so "report this generator defect" does not bury it.
        assertThat(schema.types().get("Money"))
            .isInstanceOfSatisfying(GraphitronType.UnclassifiedType.class, u ->
                assertThat(u.reason()).contains("Money").contains("codegen classpath"));
        assertThat(schema.diagnostics())
            .as("the author-caused arm keeps its own report and gains no closure diagnostic")
            .noneMatch(e -> e.message().contains("generator defect"));
    }

    @Test
    void modelRowReferencingAnUnregisteredBuiltIn_isReportedAsAGeneratorDefect() {
        // No @asConnection anywhere, so nothing demands Int and the assembled schema, whose SDL
        // never names Int either, does not carry it. Registering a row whose form references Int
        // reproduces the emitted-but-unregistered state the guard exists to catch, which no SDL can
        // produce now that the promoter demands what it mints.
        var ctx = buildContext("""
            type Film { id: ID! }
            type Query { films: [Film!]! }
            """);
        assertThat(ctx.types.get("Int")).as("fixture precondition: Int is unregistered").isNull();
        var form = GraphQLObjectType.newObject()
            .name("Tally")
            .field(GraphQLFieldDefinition.newFieldDefinition()
                .name("totalCount")
                .type(GraphQLTypeReference.typeRef("Int"))
                .build())
            .build();
        ctx.typeRegistry.register("Tally", new GraphitronType.NestingType("Tally", null, form));

        GraphitronSchemaBuilder.rejectUnregisteredScalarReferences(ctx, ctx.schema);

        assertThat(ctx.diagnostics()).singleElement().satisfies(e -> assertThat(e.message())
            .contains("scalar 'Int'")
            .contains("Tally.totalCount")
            .contains("type Int not found in schema")
            .contains("generator defect"));
    }

    private static final RunContext FIXTURE_CTX = new RunContext(
        List.of(), Path.of(""), "ScalarReferenceClosurePipelineTest", Path.of(""),
        DEFAULT_OUTPUT_PACKAGE, "no.sikt.graphitron.rewrite.nodeidfixture");

    /**
     * The classified {@link BuildContext} after the type pass, the same seam
     * {@link ConnectionPromoterTest} drives: the guard reads the registry and the assembled schema,
     * both of which this hands over fully wired.
     */
    private static BuildContext buildContext(String sdl) {
        var registry = new SchemaParser().parse(directivePrelude() + sdl);
        return GraphitronSchemaBuilder.buildContextForTests(AttributedRegistry.from(registry), FIXTURE_CTX);
    }

    private static String directivePrelude() {
        try (InputStream is = SchemaLoader.class.getResourceAsStream("directives.graphqls")) {
            if (is == null) throw new IllegalStateException("directives.graphqls not found on classpath");
            return new String(is.readAllBytes(), StandardCharsets.UTF_8) + "\n";
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}
