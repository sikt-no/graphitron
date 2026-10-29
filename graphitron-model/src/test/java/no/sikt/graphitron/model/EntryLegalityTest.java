package no.sikt.graphitron.model;

import no.sikt.graphitron.model.capture.document.GraphQLAssemblyCapture;
import no.sikt.graphitron.model.capture.document.GraphQLAstCapture;
import no.sikt.graphitron.model.capture.document.GraphQLSourceCapture;
import no.sikt.graphitron.model.capture.document.GraphitronAstCapture;
import no.sikt.graphitron.model.run.GraphIdentity;
import no.sikt.graphitron.model.run.ModelCapture;
import no.sikt.graphitron.model.run.SubjectConfig;
import no.sikt.graphitron.model.schema.input.SchemaRecipe;
import no.sikt.graphitron.model.test.ThreadConfinedStore;
import no.sikt.graphitron.model.vocabulary.EntryKind;
import org.jooq.DSLContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;

import static no.sikt.graphitron.model.Tables.GRAPHITRON_AST_CODE_REFERENCE_ENTRY;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_AST_DEFAULT_ORDER_ENTRY;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_AST_ENUM_VALUE_BINDING_ENTRY;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_AST_DEFAULT_ORDER_FIELD_ENTRY;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_AST_ERROR_ENTRY;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_AST_ERROR_HANDLER_ENTRY;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_AST_TABLE_ENTRY;
import static no.sikt.graphitron.model.Tables.GRAPHQL_AST_DIRECTIVE_APPLICATION_ENTRY;
import static no.sikt.graphitron.model.Tables.GRAPHQL_AST_ENTRY;
import static no.sikt.graphitron.model.Tables.GRAPHQL_SCHEMA_PROBLEM;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The entry stratum holds what the directive definition admits, and holds it only then.
 *
 * <p>Each case here is a corpus an author can type and a parser accepts, carrying one application
 * the definition does not admit. The assertion is in two halves and both matter. The entry is
 * absent, which is the rule. And assembly refused the same application, which is what says the rule
 * agrees with the authority rather than merely being strict: an entry this stratum drops for a
 * schema assembly is happy with would vanish from every reader that joins to it, reporting nothing.
 *
 * <p>The generic application census is the control. A row in
 * {@code graphql_ast_directive_application_entry} whose entry kind is the site says a directive of
 * this name was written here, which stays true however illegal the application is, so each case can tell a dropped decode
 * apart from a corpus that never reached capture.
 */
class EntryLegalityTest {

    /**
     * The shape the rule was measured on. {@code ErrorHandler.handler} is
     * {@code ErrorHandlerType!}, so an element omitting it is not an {@code ErrorHandler} and the
     * application is not one {@code @error} admits, whole. The element's own relation and its
     * parent's are both empty as a result, which is the difference between this rule and a filter
     * over elements.
     */
    @Test
    @DisplayName("an element missing a required field withdraws the whole application")
    void anElementMissingARequiredFieldWithdrawsTheApplication(@TempDir Path tmp) {
        ThreadConfinedStore.run(dsl -> {
            read(dsl, corpus(tmp, "missing-handler", """
                type Query { film: Film }
                type Film { title: String }
                type FilmNotFound @error(handlers: [{className: "java.lang.IllegalStateException"}]) {
                    message: String
                }
                """));
            assertThat(applications(dsl, "missing-handler", EntryKind.TYPE_DIRECTIVE, "error"))
                .as("the generic census holds the application, so an empty decode below is the rule "
                    + "rather than a corpus that never arrived")
                .isPositive();
            assertRefusedByAssembly(dsl, "missing-handler");
            assertThat(dsl.fetchCount(GRAPHITRON_AST_ERROR_HANDLER_ENTRY,
                GRAPHITRON_AST_ERROR_HANDLER_ENTRY.GRAPH_NAME.eq("missing-handler")))
                .as("the element is not an ErrorHandler, so it is not transcribed")
                .isZero();
            assertThat(dsl.fetchCount(GRAPHITRON_AST_ERROR_ENTRY,
                GRAPHITRON_AST_ERROR_ENTRY.GRAPH_NAME.eq("missing-handler")))
                .as("and neither is the application holding it, the rule being about the "
                    + "application rather than about the element")
                .isZero();
        });
    }

    /**
     * {@code FieldSort.name} is optional, because an element may sort by a {@code method:}
     * instead, so an element naming no column is a {@code FieldSort} and is transcribed with a
     * {@code NULL} name. Its reference is a row of the code-reference relation like any other.
     */
    @Test
    @DisplayName("an order element naming a method instead of a column is transcribed with no name")
    void anOrderElementNamingAMethodIsTranscribed(@TempDir Path tmp) {
        ThreadConfinedStore.run(dsl -> {
            read(dsl, corpus(tmp, "method-entry", """
                type Query {
                    films: [Film!] @defaultOrder(fields: [{method: {className: "com.example.FilmKeys", method: "latest"}}])
                }
                type Film { title: String }
                """));
            assertThat(dsl.select(GRAPHQL_SCHEMA_PROBLEM.STAGE, GRAPHQL_SCHEMA_PROBLEM.MESSAGE)
                .from(GRAPHQL_SCHEMA_PROBLEM)
                .where(GRAPHQL_SCHEMA_PROBLEM.GRAPH_NAME.eq("method-entry")).fetch())
                .as("the corpus assembles")
                .isEmpty();
            var e = GRAPHITRON_AST_DEFAULT_ORDER_FIELD_ENTRY;
            assertThat(dsl.select(e.NAME_REF).from(e).where(e.GRAPH_NAME.eq("method-entry")).fetch(e.NAME_REF))
                .as("the element is transcribed, with no name")
                .containsExactly((String) null);
            var c = GRAPHITRON_AST_CODE_REFERENCE_ENTRY;
            assertThat(dsl.select(c.CLASS_NAME, c.METHOD).from(c).where(c.GRAPH_NAME.eq("method-entry"))
                .fetch(r -> r.value1() + "#" + r.value2()))
                .as("and its method is a code reference")
                .containsExactly("com.example.FilmKeys#latest");
        });
    }

    /** An element of the wrong shape entirely, which is the case the walk used to quarantine. */
    @Test
    @DisplayName("an order element of the wrong shape withdraws the whole application")
    void anElementOfTheWrongShapeWithdrawsTheApplication(@TempDir Path tmp) {
        ThreadConfinedStore.run(dsl -> {
            read(dsl, corpus(tmp, "wrong-shape", """
                type Query { films: [Film!] @defaultOrder(fields: ["title"]) }
                type Film { title: String }
                """));
            assertApplicationWasWritten(dsl, "wrong-shape", "defaultOrder");
            assertRefusedByAssembly(dsl, "wrong-shape");
            assertThat(dsl.fetchCount(GRAPHITRON_AST_DEFAULT_ORDER_ENTRY,
                GRAPHITRON_AST_DEFAULT_ORDER_ENTRY.GRAPH_NAME.eq("wrong-shape")))
                .isZero();
        });
    }

    /** An argument the definition does not declare, which assembly calls an unknown argument. */
    @Test
    @DisplayName("an undeclared argument withdraws the application")
    void anUndeclaredArgumentWithdrawsTheApplication(@TempDir Path tmp) {
        ThreadConfinedStore.run(dsl -> {
            read(dsl, corpus(tmp, "unknown-arg", """
                type Query { films: [Film!] @defaultOrder(bogus: 1) }
                type Film { title: String }
                """));
            assertApplicationWasWritten(dsl, "unknown-arg", "defaultOrder");
            assertRefusedByAssembly(dsl, "unknown-arg");
            assertThat(dsl.fetchCount(GRAPHITRON_AST_DEFAULT_ORDER_ENTRY,
                GRAPHITRON_AST_DEFAULT_ORDER_ENTRY.GRAPH_NAME.eq("unknown-arg")))
                .isZero();
        });
    }

    /**
     * A scalar argument written as the wrong literal. {@code @defaultOrder(index:)} is a
     * {@code String}, and an integer there is not a narrower string but a different type.
     */
    @Test
    @DisplayName("a scalar argument of the wrong literal type withdraws the application")
    void aScalarOfTheWrongTypeWithdrawsTheApplication(@TempDir Path tmp) {
        ThreadConfinedStore.run(dsl -> {
            read(dsl, corpus(tmp, "wrong-scalar", """
                type Query { films: [Film!] @defaultOrder(index: 7) }
                type Film { title: String }
                """));
            assertApplicationWasWritten(dsl, "wrong-scalar", "defaultOrder");
            assertRefusedByAssembly(dsl, "wrong-scalar");
            assertThat(dsl.fetchCount(GRAPHITRON_AST_DEFAULT_ORDER_ENTRY,
                GRAPHITRON_AST_DEFAULT_ORDER_ENTRY.GRAPH_NAME.eq("wrong-scalar")))
                .isZero();
        });
    }

    /**
     * The site rule. {@code @table} is admitted on an object and {@code @defaultOrder} is not, and
     * the two applications below differ in nothing else, so the type site's own entry stands while
     * the misplaced one does not.
     */
    @Test
    @DisplayName("a directive applied where its definition does not admit it writes no entry")
    void aDirectiveAtAnIllegalSiteWritesNoEntry(@TempDir Path tmp) {
        ThreadConfinedStore.run(dsl -> {
            read(dsl, corpus(tmp, "wrong-site", """
                type Query { films: [Film!] }
                type Film @table(name: "film") @defaultOrder(fields: [{name: "title"}]) {
                  title: String
                }
                """));
            assertRefusedByAssembly(dsl, "wrong-site");
            assertThat(dsl.fetchCount(GRAPHITRON_AST_TABLE_ENTRY,
                GRAPHITRON_AST_TABLE_ENTRY.GRAPH_NAME.eq("wrong-site")))
                .as("@table is admitted on an object, so the legal neighbour is untouched and this "
                    + "case cannot pass by capturing nothing at all")
                .isOne();
            assertThat(dsl.fetchCount(GRAPHITRON_AST_DEFAULT_ORDER_ENTRY,
                GRAPHITRON_AST_DEFAULT_ORDER_ENTRY.GRAPH_NAME.eq("wrong-site")))
                .as("@defaultOrder is admitted on a field definition and nowhere else")
                .isZero();
        });
    }

    /**
     * The other direction, and the one worth most. Everything here is legal, including the parts a
     * predicate written slightly too tightly would refuse: an optional argument omitted, a
     * one-element list written without brackets, an enum token, and a defaulted argument left out.
     * A rule stricter than assembly deletes these rows and reports nothing, readers of this stratum
     * joining to it inwardly.
     */
    @Test
    @DisplayName("every legal spelling is transcribed, including the ones a tight rule would refuse")
    void legalSpellingsAreAllTranscribed(@TempDir Path tmp) {
        ThreadConfinedStore.run(dsl -> {
            read(dsl, corpus(tmp, "legal", """
                type Query {
                  byList: [Film!] @defaultOrder(fields: [{name: "title", direction: DESC}])
                  byBareObject: [Film!] @defaultOrder(fields: {name: "title"})
                  byIndex: [Film!] @defaultOrder(index: "film_title_idx")
                  byPrimaryKey: [Film!] @defaultOrder(primaryKey: true)
                }
                type Film @table(name: "film") { title: String }
                """));
            assertThat(dsl.select(GRAPHQL_SCHEMA_PROBLEM.STAGE, GRAPHQL_SCHEMA_PROBLEM.MESSAGE)
                .from(GRAPHQL_SCHEMA_PROBLEM)
                .where(GRAPHQL_SCHEMA_PROBLEM.GRAPH_NAME.eq("legal")).fetch())
                .as("the corpus assembles, so anything missing below is this rule's doing")
                .isEmpty();
            assertThat(dsl.fetchCount(GRAPHITRON_AST_DEFAULT_ORDER_ENTRY,
                GRAPHITRON_AST_DEFAULT_ORDER_ENTRY.GRAPH_NAME.eq("legal")))
                .as("all four applications are admitted, the bare object literal among them, the "
                    + "specification coercing a lone value into a one-element list")
                .isEqualTo(4);
            assertThat(dsl.fetchCount(GRAPHITRON_AST_DEFAULT_ORDER_FIELD_ENTRY,
                GRAPHITRON_AST_DEFAULT_ORDER_FIELD_ENTRY.GRAPH_NAME.eq("legal")))
                .as("and both spellings of the list contribute their element")
                .isEqualTo(2);
        });
    }

    /**
     * The enum-value site, which the rule reached second and for no better reason than that its
     * writer arrived from a session that did not have the rule. {@code @field} declares
     * {@code name} as {@code String!}, so an application that writes no name is not one the
     * definition admits, and the site is judged on the same terms as the four others.
     */
    @Test
    @DisplayName("an enum-value binding with no name written withdraws the application")
    void aBareBindingOnAnEnumValueWithdrawsTheApplication(@TempDir Path tmp) {
        ThreadConfinedStore.run(dsl -> {
            read(dsl, corpus(tmp, "bare-enum-binding", """
                type Query { films(order: FilmOrder): [Film!] }
                type Film { title: String }
                enum FilmOrder { TITLE @field }
                """));
            assertEnumValueApplicationWasWritten(dsl, "bare-enum-binding", "field");
            assertRefusedByAssembly(dsl, "bare-enum-binding");
            assertThat(dsl.fetchCount(GRAPHITRON_AST_ENUM_VALUE_BINDING_ENTRY,
                GRAPHITRON_AST_ENUM_VALUE_BINDING_ENTRY.GRAPH_NAME.eq("bare-enum-binding")))
                .as("the binding is withheld, the site judging its applications like every other")
                .isZero();
        });
    }

    /** The enum-value census, which is this site's control. */
    private static void assertEnumValueApplicationWasWritten(DSLContext dsl, String graph,
                                                            String name) {
        assertThat(applications(dsl, graph, EntryKind.ENUM_VALUE_DIRECTIVE, name))
            .as("the generic census holds the application, so an empty decode above is the rule "
                + "rather than a corpus that never arrived")
            .isPositive();
    }

    /** The application was written and reached capture, whatever became of its decode. */
    private static void assertApplicationWasWritten(DSLContext dsl, String graph, String name) {
        assertThat(applications(dsl, graph, EntryKind.FIELD_DIRECTIVE, name))
            .as("the generic census holds the application, so an empty decode below is the rule "
                + "rather than a corpus that never arrived")
            .isPositive();
    }

    /** The applications of one name written at one site, counted off the index's entry kind. */
    private static int applications(DSLContext dsl, String graph, EntryKind site, String name) {
        var d = GRAPHQL_AST_DIRECTIVE_APPLICATION_ENTRY;
        var e = GRAPHQL_AST_ENTRY;
        return dsl.fetchCount(dsl.selectOne().from(d)
            .join(e).on(e.GRAPH_NAME.eq(d.GRAPH_NAME), e.SOURCE_NAME.eq(d.SOURCE_NAME),
                e.SOURCE_LINE.eq(d.SOURCE_LINE), e.SOURCE_COLUMN.eq(d.SOURCE_COLUMN))
            .where(d.GRAPH_NAME.eq(graph))
            .and(d.NAME.eq(name))
            .and(e.ENTRY_KIND.eq(site)));
    }

    /** Assembly refused this corpus, which is the authority the rule has to agree with. */
    private static void assertRefusedByAssembly(DSLContext dsl, String graph) {
        var p = GRAPHQL_SCHEMA_PROBLEM;
        assertThat(dsl.select(p.STAGE, p.ERROR_CLASS).from(p).where(p.GRAPH_NAME.eq(graph)).fetch())
            .as("the entry is withheld because the definition does not admit the application, and "
                + "assembly says so too; a case where only this rule objects is a case where this "
                + "rule is wrong")
            .isNotEmpty();
    }

    private static Path corpus(Path parent, String name, String sdl) {
        Path directory = parent.resolve(name);
        try {
            Files.createDirectories(directory);
            Files.writeString(directory.resolve("corpus.graphqls"), sdl, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return directory;
    }

    /** The whole face, because the problem rows are half of every assertion here. */
    private static void read(DSLContext dsl, Path directory) {
        var graph = new GraphIdentity(directory.getFileName().toString(), directory);
        var config = SubjectConfig.of(new SchemaRecipe(directory.resolve("pom.xml"),
            List.of(SchemaRecipe.Binding.pattern("*.graphqls")), List.of("graphqls")));
        var readAt = LocalDateTime.now();
        ModelCapture.writeGraph(dsl, graph, readAt);
        var reading = GraphQLSourceCapture.capture(dsl, graph, config, readAt);
        var documents = reading.documents();
        GraphQLAstCapture.capture(dsl, graph, documents, readAt);
        GraphitronAstCapture.capture(dsl, graph, documents, readAt);
        GraphQLAssemblyCapture.capture(dsl, graph, reading, readAt);
    }
}
