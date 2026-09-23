package no.sikt.graphitron.rewrite;

import no.sikt.graphitron.model.run.GraphitronStore;
import no.sikt.graphitron.model.read.StoreHandle;
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
 * That an ordering no read shape can lower actually fails the build, that an editor sees it, and
 * that an ordering the classifier does lower does not, which are the claims the tiers below this one
 * cannot make. The model module's suite proves the view finds the coordinate and
 * {@link no.sikt.graphitron.rewrite.derive.UnlowerableOrderingsTest} proves the detection mints the
 * violation from a captured store; neither says the violation reaches
 * {@link GraphQLRewriteGenerator#validate()}'s verdict, and neither says the stored rejection lands
 * on the {@code diagnostic} read surface. A rule that fails no build is a rule nobody is subject to,
 * and a rejection an editor cannot see is a build error with no editor behind it.
 *
 * <p>The view's exclusion and the classifier's lowering have to name one population: a coordinate
 * the view stops rejecting and the classifier does not lower would accept a declaration and drop it.
 * So each list-shaped root form the classifier lowers (a list, an authored connection type, an
 * {@code @asConnection} expansion) is a case that builds, and the shapes it does not lower (a child
 * multitable field, a single-valued root) are cases that still fail, each negative beside its
 * positive so the gate cannot pass because everything fails or because nothing does.
 */
@PipelineTier
class UnlowerableOrderingRejectionPipelineTest {

    /**
     * A three-implementation multitable interface whose participants share an order column,
     * {@code last_update}, at one SQL type and one bound Java type, so a declaration over it lowers.
     */
    private static final String DOCUMENTS = """
        interface Document { rowId: Int }
        type Film implements Document @table(name: "film") { rowId: Int @field(name: "film_id") }
        type Actor implements Document @table(name: "actor") { rowId: Int @field(name: "actor_id") }
        type Language implements Document @table(name: "language") {
            rowId: Int @field(name: "language_id")
        }
        enum DocumentSort {
            ROW_ID @order(primaryKey: true)
            UPDATED @order(fields: [{name: "last_update"}])
        }
        input DocumentOrderBy { field: DocumentSort!  direction: SortDirection }
        """;

    /** The reported schema: the pagination, the field-level declaration and the argument together. */
    private static final String REPORTED = DOCUMENTS + """
        type Query {
            documents(order: [DocumentOrderBy] @orderBy, first: Int, after: String): [Document!]!
                @asConnection @defaultOrder(fields: [{name: "last_update"}])
        }
        """;

    /**
     * A child multitable field over participants that each reach the parent by a foreign key, so
     * the only thing wrong with it is the declaration a child fan-out cannot lower.
     */
    private static final String CHILD = """
        type Customer @table(name: "customer") { lastName: String @field(name: "last_name") }
        type Staff @table(name: "staff") { lastName: String @field(name: "last_name") }
        union Occupant = Customer | Staff
        enum OccupantSort { LAST_NAME @order(fields: [{name: "last_name"}]) }
        input OccupantOrderBy { field: OccupantSort!  direction: SortDirection }
        type Store @table(name: "store") {
            occupants(order: OccupantOrderBy @orderBy): [Occupant!]!
                @defaultOrder(fields: [{name: "last_name"}])
        }
        type Query { stores: [Store!]! }
        """;

    /**
     * The reporter's own ask: the schema that was refused while the root read could not honour its
     * ordering builds, both routes lowered onto the participant branches.
     */
    @Test
    void theReportedSchemaBuilds(@TempDir Path tmp) {
        assertThatCode(() -> validate(tmp, REPORTED))
            .as("an @asConnection root carrying @defaultOrder and an @orderBy argument lowers both")
            .doesNotThrowAnyException();
    }

    /** A plain list root lowers its declaration. */
    @Test
    void aDeclaredListRootBuilds(@TempDir Path tmp) {
        assertThatCode(() -> validate(tmp, DOCUMENTS + """
            type Query {
                documents: [Document!]! @defaultOrder(fields: [{name: "last_update"}])
            }
            """))
            .doesNotThrowAnyException();
    }

    /** A root returning an authored connection type lowers its declaration. */
    @Test
    void aDeclaredAuthoredConnectionRootBuilds(@TempDir Path tmp) {
        assertThatCode(() -> validate(tmp, DOCUMENTS + """
            type DocumentEdge { node: Document!  cursor: String! }
            type DocumentConnection { edges: [DocumentEdge!]!  pageInfo: PageInfo! }
            type PageInfo {
                hasNextPage: Boolean!  hasPreviousPage: Boolean!
                startCursor: String  endCursor: String
            }
            type Query {
                documents(first: Int, after: String): DocumentConnection!
                    @defaultOrder(fields: [{name: "last_update"}])
            }
            """))
            .doesNotThrowAnyException();
    }

    /**
     * The same paginated multitable root with no ordering declared builds clean, as it always has:
     * the emitter orders the combined result on a synthetic key built from each participant's
     * primary key, so what is available there is what is delivered.
     */
    @Test
    void theSameShapeWithNoDeclarationBuildsClean(@TempDir Path tmp) {
        assertThatCode(() -> validate(tmp, DOCUMENTS + """
            type Query {
                documents(first: Int, after: String): [Document!]! @asConnection
            }
            """))
            .as("a multitable read declares nothing and delivers participant primary-key order")
            .doesNotThrowAnyException();
    }

    /**
     * A child multitable field still fails the build, one rejection per route, each naming its own
     * remedy: the lowering is root-only, and a child read is one statement per participant with no
     * branch carrying the declaration.
     */
    @Test
    void aDeclaredChildMultitableFieldFailsTheBuild(@TempDir Path tmp) {
        assertThatThrownBy(() -> validate(tmp, CHILD))
            .isInstanceOf(ValidationFailedException.class)
            .satisfies(e -> assertThat(((ValidationFailedException) e).errors())
                .extracting(ValidationError::message)
                .anyMatch(m -> m.contains("Field 'Store.occupants': @defaultOrder declares an"
                    + " ordering this field cannot honour")
                    && m.contains("one statement per participant (Customer, Staff)"))
                .anyMatch(m -> m.contains("argument 'order' carries @orderBy")));
    }

    /**
     * A single-valued multitable root resolves no ordering to lower, so a declaration there is
     * still refused rather than accepted and dropped.
     */
    @Test
    void aDeclaredSingleValuedRootFailsTheBuild(@TempDir Path tmp) {
        assertThatThrownBy(() -> validate(tmp, DOCUMENTS + """
            type Query {
                document: Document @defaultOrder(fields: [{name: "last_update"}])
            }
            """))
            .isInstanceOf(ValidationFailedException.class)
            .satisfies(e -> assertThat(((ValidationFailedException) e).errors())
                .extracting(ValidationError::message)
                .anyMatch(m -> m.contains("Field 'Query.document': @defaultOrder declares an"
                    + " ordering this field cannot honour")));
    }

    /**
     * What the editor reads. The rejection is stored with kind {@code DEFERRED}, so the
     * {@code diagnostic} view derives {@code actionable = FALSE}: the schema is well formed and the
     * remedy is a workaround rather than a fix, which is exactly how an editor should triage a
     * declaration waiting on a lowering.
     */
    @Test
    void theStoredRejectionReachesTheDiagnosticSurfaceAsNotActionable(@TempDir Path tmp) {
        try (var store = CapturedStore.ofCatalog(tmp, CHILD, jooq())) {
            var rows = store.dsl().selectFrom(DIAGNOSTIC)
                .where(DIAGNOSTIC.GRAPH_NAME.eq(CapturedStore.GRAPH),
                    DIAGNOSTIC.COORDINATE.eq("Store.occupants"),
                    DIAGNOSTIC.KIND.eq("DEFERRED"))
                .orderBy(DIAGNOSTIC.MESSAGE)
                .fetch();

            assertThat(rows)
                .as("one diagnostic row per rejected availability route")
                .hasSize(2);
            assertThat(rows).allSatisfy(row -> {
                assertThat(row.getSeverity()).isEqualTo("error");
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
        var ctx = new RunContext(
            List.of(new SchemaInput(SchemaSource.file(schema), Optional.empty(), Optional.empty())),
            tmp, "UnlowerableOrderingRejectionPipelineTest",
            tmp,
            DEFAULT_OUTPUT_PACKAGE,
            DEFAULT_JOOQ_PACKAGE
        );
        try (var store = GraphitronStore.captured(ctx)) {
            new GraphQLRewriteGenerator(ctx, new StoreHandle(store.dsl(), ctx.graphName()))
                .validate();
        }
    }

    private static JooqCatalog jooq() {
        var ctx = testContext();
        return new JooqCatalog(ctx.jooqPackage(), ctx.codegenLoader());
    }
}
