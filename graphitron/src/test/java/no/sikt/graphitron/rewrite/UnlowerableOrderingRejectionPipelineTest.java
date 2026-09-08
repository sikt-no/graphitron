package no.sikt.graphitron.rewrite;

import no.sikt.graphitron.model.config.RunContext;
import no.sikt.graphitron.model.diagnostics.ValidationError;
import no.sikt.graphitron.model.diagnostics.ValidationFailedException;
import no.sikt.graphitron.model.jooq.JooqCatalog;
import no.sikt.graphitron.model.schema.input.SchemaInput;
import no.sikt.graphitron.model.schema.input.SchemaSource;
import no.sikt.graphitron.model.test.CapturedStore;
import no.sikt.graphitron.rewrite.test.tier.PipelineTier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static no.sikt.graphitron.common.configuration.TestConfiguration.DEFAULT_JOOQ_PACKAGE;
import static no.sikt.graphitron.common.configuration.TestConfiguration.DEFAULT_OUTPUT_PACKAGE;
import static no.sikt.graphitron.common.configuration.TestConfiguration.testContext;
import static no.sikt.graphitron.model.Tables.DIAGNOSTIC;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * That an ordering no read shape can lower actually fails the build, and that an editor sees it,
 * which are the two claims the tiers below this one cannot make. The model module's suite proves
 * the view finds the coordinate and {@link no.sikt.graphitron.rewrite.derive.UnlowerableOrderingsTest}
 * proves the detection mints the violation from a captured store; neither says the violation reaches
 * {@link GraphQLRewriteGenerator#validate()}'s verdict, and neither says the stored rejection lands
 * on the {@code diagnostic} read surface. A rule that fails no build is a rule nobody is subject to,
 * and a rejection an editor cannot see is a build error with no editor behind it.
 *
 * <p>Both are asserted with their negatives beside them: the reported schema throws and the same
 * shape without the declaration builds clean, which is what keeps the gate from passing because
 * everything fails.
 *
 * <p>This is a breaking change and the fixtures say so: every schema here compiled before the rule
 * landed, and served rows in participant primary-key order whatever the client asked for.
 */
@PipelineTier
class UnlowerableOrderingRejectionPipelineTest {

    /** The three-implementation multitable interface the report arrived on. */
    private static final String DOCUMENTS = """
        interface Document { rowId: Int }
        type Film implements Document @table(name: "film") { rowId: Int @field(name: "film_id") }
        type Actor implements Document @table(name: "actor") { rowId: Int @field(name: "actor_id") }
        type Language implements Document @table(name: "language") {
            rowId: Int @field(name: "language_id")
        }
        """;

    /** The reported schema: the pagination, the field-level declaration and the argument together. */
    private static final String REPORTED = DOCUMENTS + """
        enum DocumentSort { ROW_ID @order(primaryKey: true) }
        input DocumentOrderBy { field: DocumentSort!  direction: SortDirection }
        type Query {
            documents(order: [DocumentOrderBy] @orderBy, first: Int, after: String): [Document!]!
                @asConnection @defaultOrder(fields: [{name: "rowId"}])
        }
        """;

    /**
     * The reporter's own fallback ask: a schema that compiles today and serves every page in
     * participant primary-key order now stops the build instead, with a message naming the
     * coordinate, the declaration and the reason.
     */
    @Test
    void theReportedSchemaFailsTheBuild(@TempDir Path tmp) throws IOException {
        assertThatThrownBy(() -> validate(tmp, REPORTED))
            .isInstanceOf(ValidationFailedException.class)
            .satisfies(e -> assertThat(((ValidationFailedException) e).errors())
                .extracting(ValidationError::message)
                .as("both halves of the declaration reach the build's verdict, each with its own"
                    + " remedy")
                .anyMatch(m -> m.contains("Field 'Query.documents': @defaultOrder declares an"
                    + " ordering this field cannot honour")
                    && m.contains("one statement per participant (Actor, Film, Language)"))
                .anyMatch(m -> m.contains("argument 'order' carries @orderBy")));
    }

    /**
     * The same paginated multitable root with no ordering declared builds clean, and that is the
     * property the whole keying rests on rather than a convenience: the emitter orders the combined
     * result on a synthetic key built from each participant's primary key, so what is available
     * there is what is delivered.
     */
    @Test
    void theSameShapeWithNoDeclarationBuildsClean(@TempDir Path tmp) throws IOException {
        assertThatCode(() -> validate(tmp, DOCUMENTS + """
            type Query {
                documents(first: Int, after: String): [Document!]! @asConnection
            }
            """))
            .as("a multitable read declares nothing and delivers participant primary-key order")
            .doesNotThrowAnyException();
    }

    /**
     * What the editor reads. The rejection is stored with kind {@code DEFERRED}, so the
     * {@code diagnostic} view derives {@code actionable = FALSE}: the schema is well formed and the
     * remedy is a workaround rather than a fix, which is exactly how an editor should triage a
     * declaration waiting on a lowering. Without this case the phase ships a build error and an
     * editor that stays silent.
     */
    @Test
    void theStoredRejectionReachesTheDiagnosticSurfaceAsNotActionable(@TempDir Path tmp) {
        try (var store = CapturedStore.ofCatalog(tmp, REPORTED, jooq())) {
            var rows = store.dsl().selectFrom(DIAGNOSTIC)
                .where(DIAGNOSTIC.GRAPH_NAME.eq(CapturedStore.GRAPH),
                    DIAGNOSTIC.COORDINATE.eq("Query.documents"))
                .orderBy(DIAGNOSTIC.MESSAGE)
                .fetch();

            assertThat(rows)
                .as("one diagnostic row per rejected availability route")
                .hasSize(2);
            assertThat(rows).allSatisfy(row -> {
                assertThat(row.getSeverity()).isEqualTo("error");
                assertThat(row.getKind()).isEqualTo("DEFERRED");
                assertThat(row.getActionable())
                    .as("recognised but not yet generator-supported, a workaround rather than a"
                        + " schema fix")
                    .isFalse();
                assertThat(row.getVariant())
                    .as("the variant is minted from the rejection's own class, not spelled in SQL")
                    .isEqualTo("Rejection.Deferred");
                assertThat(row.getSourceLine())
                    .as("the declaring directive's own position, so an editor underlines what the"
                        + " author wrote")
                    .isNotNull();
            });
            assertThat(rows).extracting(row -> row.getMessage())
                .anyMatch(m -> m.contains("@defaultOrder declares an ordering"))
                .anyMatch(m -> m.contains("argument 'order' carries @orderBy"));
        }
    }

    // ===== Helpers =====

    /** Runs the build-time validate pass over one SDL fixture, capture and detections included. */
    private static void validate(Path tmp, String sdl) throws IOException {
        Path schema = tmp.resolve("schema.graphqls");
        Files.writeString(schema, sdl);
        new GraphQLRewriteGenerator(new RunContext(
            List.of(new SchemaInput(SchemaSource.file(schema), Optional.empty(), Optional.empty())),
            tmp, "UnlowerableOrderingRejectionPipelineTest",
            tmp,
            DEFAULT_OUTPUT_PACKAGE,
            DEFAULT_JOOQ_PACKAGE
        )).validate();
    }

    private static JooqCatalog jooq() {
        var ctx = testContext();
        return new JooqCatalog(ctx.jooqPackage(), ctx.codegenLoader());
    }
}
