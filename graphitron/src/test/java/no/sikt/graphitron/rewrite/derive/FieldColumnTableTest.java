package no.sikt.graphitron.rewrite.derive;

import no.sikt.graphitron.model.boot.GraphitronModelStore;
import no.sikt.graphitron.rewrite.JooqCatalog;
import no.sikt.graphitron.rewrite.NodeDeclaration;
import no.sikt.graphitron.rewrite.TestSchemaHelper;
import no.sikt.graphitron.rewrite.capture.FactCapture;
import no.sikt.graphitron.rewrite.schema.RewriteSchemaLoader;
import no.sikt.graphitron.rewrite.schema.input.SchemaSource;
import no.sikt.graphitron.rewrite.test.tier.PipelineTier;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static no.sikt.graphitron.common.configuration.TestConfiguration.testContext;
import static no.sikt.graphitron.model.Tables.INTENT_FIELD_COLUMN_TABLE;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The registered agreement anchor for {@code intent_field_column_table}: which table a column name
 * written at a field's site resolves against, when that table is not the one the field's own parent
 * is bound to.
 *
 * <p>Half of these cases assert that a coordinate produces <em>no</em> row, which is the view's
 * central claim rather than a gap in it. The relation only overrides a parent's own scope, so
 * absence is the answer "the parent's scope stands", and a case that pins absence is pinning the
 * boundary of the override. Reading those cases as untested behaviour gets the relation backwards.
 *
 * <p>Every case captures real SDL against the test catalog. The resolutions the view layers on
 * (a path's terminal element, a type's table binding, the authored claims) are all products of
 * capture, so a seeded fixture could assert a combination no schema produces; where a case needs a
 * claim conflict or an unresolvable path, the SDL is written to produce it.
 */
@PipelineTier
class FieldColumnTableTest {

    @TempDir
    Path tmp;

    // ===== A path names the table =====

    /**
     * The single-element case: the column named on the field lives on the path's terminal table,
     * not on the parent's own, which is the whole reason this relation exists.
     */
    @Test
    void aReferencePathResolvesItsTerminalTable() {
        withCapturedStore("""
            type Film @table(name: "film") {
                languageName: String @field(name: "name") @reference(path: [{table: "language"}])
            }
            type Query { films: [Film] }
            """, dsl -> {
            var row = row(dsl, "Film", "languageName").orElseThrow();
            assertThat(row.get(INTENT_FIELD_COLUMN_TABLE.DISPOSITION)).isEqualTo("RESOLVE");
            assertThat(row.get(INTENT_FIELD_COLUMN_TABLE.BASIS)).isEqualTo("PATH_TERMINAL");
            assertThat(row.get(INTENT_FIELD_COLUMN_TABLE.TABLE_NAME))
                .isEqualToIgnoringCase("language");
        });
    }

    /** A path of several elements resolves the last one; the elements between are not answers. */
    @Test
    void aMultiElementPathResolvesItsLastElement() {
        withCapturedStore("""
            type Film @table(name: "film") {
                actorName: String @field(name: "first_name")
                    @reference(path: [{table: "film_actor"}, {table: "actor"}])
            }
            type Query { films: [Film] }
            """, dsl -> {
            var row = row(dsl, "Film", "actorName").orElseThrow();
            assertThat(row.get(INTENT_FIELD_COLUMN_TABLE.BASIS)).isEqualTo("PATH_TERMINAL");
            assertThat(row.get(INTENT_FIELD_COLUMN_TABLE.TABLE_NAME)).isEqualToIgnoringCase("actor");
        });
    }

    /**
     * An authored path outranks the field's own named type: the path is where the author said the
     * value comes from, so a field whose named type is bound elsewhere still reads the terminal.
     */
    @Test
    void anAuthoredPathOutranksTheNamedTypesOwnTable() {
        withCapturedStore("""
            type Film @table(name: "film") {
                credits: Actor @reference(path: [{table: "film_actor"}])
            }
            type Actor @table(name: "actor") { firstName: String }
            type Query { films: [Film] }
            """, dsl -> {
            var row = row(dsl, "Film", "credits").orElseThrow();
            assertThat(row.get(INTENT_FIELD_COLUMN_TABLE.BASIS))
                .as("both rules could fire here; the path is the one that wins")
                .isEqualTo("PATH_TERMINAL");
            assertThat(row.get(INTENT_FIELD_COLUMN_TABLE.TABLE_NAME))
                .isEqualToIgnoringCase("film_actor");
        });
    }

    /**
     * A path naming a table the census does not hold reaches nothing, and the silence is the point:
     * without it the parent's own table would stand in and offer columns from the wrong end of a
     * join the author is still writing.
     */
    @Test
    void aPathReachingNoTableIsSilentRatherThanFallingBack() {
        withCapturedStore("""
            type Film @table(name: "film") {
                languageName: String @field(name: "name") @reference(path: [{table: "no_such_table"}])
            }
            type Query { films: [Film] }
            """, dsl -> {
            var row = row(dsl, "Film", "languageName").orElseThrow();
            assertThat(row.get(INTENT_FIELD_COLUMN_TABLE.DISPOSITION)).isEqualTo("SILENT");
            assertThat(row.get(INTENT_FIELD_COLUMN_TABLE.BASIS)).isEqualTo("UNRESOLVED_PATH");
            assertThat(row.get(INTENT_FIELD_COLUMN_TABLE.TABLE_NAME)).isNull();
        });
    }

    // ===== A named type names the table =====

    /**
     * The no-path case: a field navigating to a table-bound type resolves that type's table, which
     * is where an ordering column named on a list field lives.
     */
    @Test
    void aFieldNavigatingToATableBoundTypeResolvesThatTypesTable() {
        withCapturedStore("""
            type Film @table(name: "film") {
                languages: [Language!]!
            }
            type Language @table(name: "language") { name: String }
            type Query { films: [Film] }
            """, dsl -> {
            var row = row(dsl, "Film", "languages").orElseThrow();
            assertThat(row.get(INTENT_FIELD_COLUMN_TABLE.DISPOSITION)).isEqualTo("RESOLVE");
            assertThat(row.get(INTENT_FIELD_COLUMN_TABLE.BASIS)).isEqualTo("NAMED_TYPE_TABLE");
            assertThat(row.get(INTENT_FIELD_COLUMN_TABLE.TABLE_NAME))
                .isEqualToIgnoringCase("language");
        });
    }

    /**
     * A macro that rewrote the field's type expression does not move the answer: the rule reads the
     * type the author wrote, so a connection field still resolves its element's table rather than
     * the synthesized wrapper's, which is bound to nothing.
     */
    @Test
    void aConnectionFieldResolvesItsElementsTable() {
        withCapturedStore("""
            type Film @table(name: "film") {
                languages: [Language!]! @asConnection
            }
            type Language @table(name: "language") { name: String }
            type Query { films: [Film] }
            """, dsl -> {
            var row = row(dsl, "Film", "languages").orElseThrow();
            assertThat(row.get(INTENT_FIELD_COLUMN_TABLE.BASIS)).isEqualTo("NAMED_TYPE_TABLE");
            assertThat(row.get(INTENT_FIELD_COLUMN_TABLE.TABLE_NAME))
                .as("the field's own named type is now the connection wrapper")
                .isEqualToIgnoringCase("language");
        });
    }

    /**
     * A scalar field contributes no row. Its column lives on its parent's own table, and a reader
     * already holding that binding needs no relation to be told so.
     */
    @Test
    void aScalarFieldLeavesItsParentsScopeStanding() {
        withCapturedStore("""
            type Film @table(name: "film") {
                title: String
            }
            type Query { films: [Film] }
            """, dsl -> assertThat(row(dsl, "Film", "title")).isEmpty());
    }

    /** A root's field navigates from no scope of its own, so the named-type rule does not fire. */
    @Test
    void aRootFieldNavigatesFromNoScope() {
        withCapturedStore("""
            type Film @table(name: "film") { title: String }
            type Query { films: [Film] }
            """, dsl -> assertThat(row(dsl, "Query", "films")).isEmpty());
    }

    /**
     * A named type of any kind but OBJECT is a different question. A table-bound interface is one
     * table per participant, not one table, so the field's column names do not resolve against it.
     */
    @Test
    void aTableBoundInterfaceIsADifferentQuestion() {
        withCapturedStore("""
            interface Media @table(name: "film") { title: String }
            type Film implements Media @table(name: "film") @discriminator(value: "FILM") {
                title: String
                related: Media
            }
            type Query { films: [Film] }
            """, dsl -> assertThat(row(dsl, "Film", "related")).isEmpty());
    }

    /**
     * An authored claim diverts the field: its value comes from the claim, not from the type it
     * names, so the named-type rule stands down. The guard is an anti-join against the claims
     * rather than a list of directives, so it covers every claim the vocabulary carries.
     */
    @Test
    void anAuthoredClaimDivertsTheFieldFromItsNamedType() {
        withCapturedStore("""
            type Film @table(name: "film") {
                languages: [Language!]!
                    @service(service: {className: "no.example.Svc", method: "langs"})
            }
            type Language @table(name: "language") { name: String }
            type Query { films: [Film] }
            """, dsl -> assertThat(row(dsl, "Film", "languages")).isEmpty());
    }

    // ===== The coordinate's claims are contested =====

    /**
     * A conflicted coordinate is silent even where a rule would otherwise resolve a table: while
     * the author's claims disagree, no column name at the site has a settled scope to resolve in.
     */
    @Test
    void aConflictedCoordinateIsSilentEvenWhereARuleWouldResolve() {
        withCapturedStoreAndClaimDomain("""
            type Film @table(name: "film") {
                languageName: String
                    @reference(path: [{table: "language"}])
                    @service(service: {className: "%s", method: "get"})
                    @externalField(reference: {className: "%s", method: "rating"})
            }
            type Query { film: Film }
            """.formatted(SERVICE_STUB, EXTERNAL_FIELD_STUB), dsl -> {
            var row = row(dsl, "Film", "languageName").orElseThrow();
            assertThat(row.get(INTENT_FIELD_COLUMN_TABLE.DISPOSITION)).isEqualTo("SILENT");
            assertThat(row.get(INTENT_FIELD_COLUMN_TABLE.BASIS))
                .as("the path resolves to language, and the contested claims still win")
                .isEqualTo("CONFLICTED");
            assertThat(row.get(INTENT_FIELD_COLUMN_TABLE.TABLE_NAME)).isNull();
        });
    }

    // ===== Partition =====

    /** The graph partition: one workspace's graphs do not read each other's resolutions. */
    @Test
    void aSiblingGraphReadsNone() {
        withCapturedStore("""
            type Film @table(name: "film") {
                languages: [Language!]!
            }
            type Language @table(name: "language") { name: String }
            type Query { films: [Film] }
            """, dsl -> {
            assertThat(row(dsl, "Film", "languages")).isPresent();
            assertThat(dsl.fetchCount(INTENT_FIELD_COLUMN_TABLE,
                INTENT_FIELD_COLUMN_TABLE.GRAPH_NAME.eq("other"))).isZero();
        });
    }

    // ===== Helpers =====

    private static final String GRAPH = "FieldColumnTableTest";
    private static final String SERVICE_STUB = "no.sikt.graphitron.rewrite.TestServiceStub";
    private static final String EXTERNAL_FIELD_STUB =
        "no.sikt.graphitron.rewrite.TestExternalFieldStub";

    /** The one row for a coordinate, the relation's grain being the field. */
    private static Optional<Record> row(DSLContext dsl, String typeName, String fieldName) {
        var rows = dsl.select(INTENT_FIELD_COLUMN_TABLE.fields())
            .from(INTENT_FIELD_COLUMN_TABLE)
            .where(INTENT_FIELD_COLUMN_TABLE.GRAPH_NAME.eq(GRAPH))
            .and(INTENT_FIELD_COLUMN_TABLE.TYPE_NAME.eq(typeName))
            .and(INTENT_FIELD_COLUMN_TABLE.FIELD_NAME.eq(fieldName))
            .fetch();
        assertThat(rows.size())
            .as("the relation carries at most one row per coordinate")
            .isLessThanOrEqualTo(1);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.getFirst());
    }

    private void withCapturedStore(String sdl, java.util.function.Consumer<DSLContext> body) {
        withCapturedStore(sdl, false, body);
    }

    /**
     * Capture plus the walk's claim-reach rows. The conflict resolution this view reads gates on
     * that relation, and capture alone does not write it, so the one case about a contested
     * coordinate needs the walk's own reach beside the facts.
     */
    private void withCapturedStoreAndClaimDomain(
        String sdl, java.util.function.Consumer<DSLContext> body
    ) {
        withCapturedStore(sdl, true, body);
    }

    private void withCapturedStore(
        String sdl, boolean withClaimDomain, java.util.function.Consumer<DSLContext> body
    ) {
        var ctx = testContext();
        var jooq = new JooqCatalog(ctx.jooqPackage(), ctx.codegenLoader());
        try (var store = GraphitronModelStore.open()) {
            var schemaFile = write(tmp, sdl);
            var registry = RewriteSchemaLoader.load(List.of(SchemaSource.file(schemaFile)));
            FactCapture.capture(store.dsl(), new FactCapture.GraphIdentity(GRAPH, tmp),
                FactCapture.SubjectConfig.none(), registry, TestSchemaHelper.attribution(schemaFile),
                jooq, List.of(), new NodeDeclaration(null));
            if (withClaimDomain) {
                ClaimDomainRows.write(store.dsl(), GRAPH,
                    ClaimDomain.of(TestSchemaHelper.buildSchema(sdl)));
            }
            body.accept(store.dsl());
        }
    }

    private static Path write(Path directory, String sdl) {
        Path file = directory.resolve("fixture.graphqls");
        try {
            Files.createDirectories(directory);
            Files.writeString(file, sdl);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return file;
    }
}
