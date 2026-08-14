package no.sikt.graphitron.lsp;

import io.github.treesitter.jtreesitter.Parser;
import io.github.treesitter.jtreesitter.Point;
import no.sikt.graphitron.lsp.completions.CompletionContext;
import no.sikt.graphitron.lsp.completions.ReferenceCompletions;
import no.sikt.graphitron.lsp.parsing.Directives;
import no.sikt.graphitron.lsp.parsing.GraphqlLanguage;
import no.sikt.graphitron.lsp.parsing.LspVocabulary;
import no.sikt.graphitron.model.read.StoreHandle;
import org.eclipse.lsp4j.CompletionItem;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Coverage for {@code @reference(path: [{key: "..."}, {table: "..."}])}. The candidates are the
 * {@code sql_referential_constraint} rows touching the enclosing type's table, in either direction,
 * and both halves of that are store reads: the enclosing type's binding resolves through
 * {@code intent_bound_table} and the keys through the catalog census.
 *
 * <p>Both halves are captured, so the binding is one a build would produce. The incumbent's fixtures
 * paired a real census with a hand-built type classification, which is how they came to assert an
 * answer for a binding the classifier could not have reached: an unqualified name two schemas both
 * declare is its {@code Ambiguous} verdict and therefore no table at all, so the arm those fixtures
 * described as offering both schemas' keys would have offered none.
 *
 * <p>The census comes from the fixture modules' real generated jOOQ models, so a constraint name here
 * is one an author could actually have typed, and the multi-schema model supplies the two shapes a
 * single-schema one cannot: a name declared in two schemas, and a name declared twice inside one.
 */
class ReferenceCompletionsTest {

    private static final LspVocabulary VOCAB = LspVocabulary.load();

    @Test
    void keyCompletionReturnsForeignKeysTouchingTheEnclosingTable(@TempDir Path tmp) {
        try (var fixture = StoreFixture.ofCatalog(tmp, bound("film"))) {
            var items = items(fixture, keySite());

            assertThat(labels(items))
                // The keys film declares...
                .contains("film_language_id_fkey", "film_original_language_id_fkey")
                // ...and the keys other tables declare against it.
                .contains("film_actor_film_id_fkey", "inventory_film_id_fkey")
                // Ordered by declaring schema then constraint name, which is stateable where the
                // projection's order was the generated Tables class's field order.
                .containsSubsequence("film_actor_film_id_fkey", "film_language_id_fkey",
                    "inventory_film_id_fkey");
            assertThat(detailOf(items, "film_language_id_fkey")).isEqualTo("→ language");
            assertThat(detailOf(items, "film_actor_film_id_fkey")).isEqualTo("← film_actor");
        }
    }

    /**
     * The generated {@code Keys} constant is documented rather than offered: {@code key:} resolves
     * both namespaces, but only one of them is what the manual teaches and what every constraint has.
     */
    @Test
    void theGeneratedConstantIsDocumented(@TempDir Path tmp) {
        try (var fixture = StoreFixture.ofCatalog(tmp, bound("film"))) {
            assertThat(documentationOf(items(fixture, keySite()), "film_language_id_fkey"))
                .isEqualTo(
                    "Also resolves under the generated constant FILM__FILM_LANGUAGE_ID_FKEY.");
        }
    }

    /**
     * A self-referencing key satisfies both halves of the direction predicate, and is one row of the
     * relation, so it is offered once. Outbound is what it says, which is also what the projection
     * said: its inbound pass skipped the table's own name.
     */
    @Test
    void aSelfReferencingKeyIsOfferedOnce(@TempDir Path tmp) {
        try (var fixture = StoreFixture.ofCatalog(tmp, bound("category"))) {
            var items = items(fixture, keySite());

            assertThat(labels(items))
                .filteredOn("category_parent_category_id_fkey"::equals).hasSize(1);
            assertThat(detailOf(items, "category_parent_category_id_fkey")).isEqualTo("→ category");
        }
    }

    /**
     * An unqualified reference two schemas both answer binds to both tables, and each contributes its
     * own keys. A constraint name they both declare is then offered once per schema, qualified: the
     * qualifier is grammar {@code key:} accepts and treats as stated intent, so each label resolves to
     * exactly the key it came from, while a name only one schema declares stays bare as the manual
     * writes it.
     */
    @Test
    void anAmbiguousBindingOffersEachCandidatesKeys(@TempDir Path tmp) {
        try (var fixture = StoreFixture.ofMultiSchemaCatalog(tmp, bound("event"))) {
            assertThat(labels(items(fixture, keySite())))
                .contains("multischema_a.note_event_fk", "multischema_b.note_event_fk")
                .doesNotContain("note_event_fk")
                .contains("event_log_event_id_fkey");
        }
    }

    /**
     * Two keys of one name inside one schema, which PostgreSQL permits because constraint names are
     * table-scoped, share every spelling an author has. One candidate then, naming both joins rather
     * than picking one: which key the value resolves to is the resolver's own table-scoping, not
     * something the popup can settle.
     */
    @Test
    void oneSpellingCoveringTwoKeysNamesBothJoins(@TempDir Path tmp) {
        try (var fixture = StoreFixture.ofMultiSchemaCatalog(tmp, bound("gizmo"))) {
            var items = items(fixture, keySite());

            assertThat(labels(items)).containsExactly("dup_gizmo_fk");
            assertThat(detailOf(items, "dup_gizmo_fk")).isEqualTo("← dup_one, ← dup_two");
        }
    }

    /**
     * The binding is keyed on the declared type name, so an {@code extend type} site resolves through
     * the base declaration's {@code @table} in another file. That is what the incumbent's name-keyed
     * projection existed for, and it survives the move to a name-keyed relation.
     */
    @Test
    void anExtensionSiteResolvesThroughTheTypesBinding(@TempDir Path tmp) {
        String buffer = """
            extend type Foo {
                extra: Int @reference(path: [{key: ""}])
            }
            """;

        try (var fixture = StoreFixture.ofCatalog(tmp, bound("film"))) {
            assertThat(labels(items(fixture, buffer)))
                .contains("film_language_id_fkey", "film_actor_film_id_fkey");
        }
    }

    @Test
    void tableCompletionRoutesThroughTableCompletionsNotHere(@TempDir Path tmp) {
        // ReferenceCompletions narrows to the FK (CatalogFkBinding) arm. Table completion at
        // @reference(path: [{table:}]) is the ReferenceElement.table coordinate's
        // CatalogTableBinding, served by TableCompletions. ReferenceCompletions returns empty here.
        String buffer = """
            type Foo @table(name: "film") {
                bar: Int @reference(path: [{table: ""}])
            }
            """;

        try (var fixture = StoreFixture.ofCatalog(tmp, bound("film"))) {
            assertThat(items(fixture, buffer)).isEmpty();
        }
    }

    @Test
    void cursorOutsideNestedValueReturnsEmpty(@TempDir Path tmp) {
        // Cursor on the nested field's key name, not on its value.
        String buffer = """
            type Foo @table(name: "film") {
                bar: Int @reference(path: [{key: "film_language_id_fkey"}])
            }
            """;
        var cursor = new Point(1, buffer.split("\n")[1].indexOf("key:") + 1);

        try (var fixture = StoreFixture.ofCatalog(tmp, bound("film"))) {
            assertThat(items(fixture.handle(), buffer, cursor)).isEmpty();
        }
    }

    /**
     * A reference the census cannot answer is a binding with no candidates, which the view reports by
     * holding no rows for the type rather than by any lookup the arm has to make first.
     */
    @Test
    void aReferenceNoTableAnswersOffersNothing(@TempDir Path tmp) {
        try (var fixture = StoreFixture.ofCatalog(tmp, bound("missing"))) {
            assertThat(items(fixture, keySite())).isEmpty();
        }
    }

    /** A type carrying no {@code @table} at all has no binding to read keys around. */
    @Test
    void aTypeWithNoTableDirectiveOffersNothing(@TempDir Path tmp) {
        String sdl = """
            type Foo { bar: Int }
            type Query { foo: Foo }
            """;

        try (var fixture = StoreFixture.ofCatalog(tmp, sdl)) {
            assertThat(items(fixture, keySite())).isEmpty();
        }
    }

    /**
     * The binding is one graph's, and the rows are there to be missed: the same store holds
     * {@code Foo}'s binding under the captured graph, and a handle scoped to another graph reads none
     * of it.
     */
    @Test
    void aSiblingGraphReadsNoneOfTheBinding(@TempDir Path tmp) {
        try (var fixture = StoreFixture.ofCatalog(tmp, bound("film"))) {
            assertThat(items(fixture.handle(), keySite(), keyCursor(keySite()))).isNotEmpty();
            assertThat(items(fixture.handleFor("other"), keySite(), keyCursor(keySite()))).isEmpty();
        }
    }

    @Test
    void unknownNestedFieldReturnsEmpty(@TempDir Path tmp) {
        // 'condition' is a real field on ReferenceElement but autocomplete for it is not plugged in;
        // the dispatcher returns empty.
        String buffer = """
            type Foo @table(name: "film") {
                bar: Int @reference(path: [{condition: ""}])
            }
            """;

        try (var fixture = StoreFixture.ofCatalog(tmp, bound("film"))) {
            assertThat(items(fixture, buffer)).isEmpty();
        }
    }

    /** The captured schema: {@code Foo} bound to {@code tableName}, which is the arm's whole input. */
    private static String bound(String tableName) {
        return """
            type Foo @table(name: "%s") { bar: Int }
            type Query { foo: Foo }
            """.formatted(tableName);
    }

    /** A key site inside {@code Foo}, cursor going in the empty value. */
    private static String keySite() {
        return """
            type Foo {
                bar: Int @reference(path: [{key: ""}])
            }
            """;
    }

    private static Point keyCursor(String buffer) {
        return new Point(1, buffer.split("\n")[1].indexOf("\"\"") + 1);
    }

    private static List<CompletionItem> items(StoreFixture fixture, String buffer) {
        return items(fixture.handle(), buffer, keyCursor(buffer));
    }

    private static List<String> labels(List<CompletionItem> items) {
        return items.stream().map(CompletionItem::getLabel).toList();
    }

    private static String detailOf(List<CompletionItem> items, String label) {
        return itemAt(items, label).getDetail();
    }

    private static String documentationOf(List<CompletionItem> items, String label) {
        var documentation = itemAt(items, label).getDocumentation();
        return documentation == null ? "" : documentation.getRight().getValue();
    }

    private static CompletionItem itemAt(List<CompletionItem> items, String label) {
        return items.stream().filter(i -> label.equals(i.getLabel())).findFirst()
            .orElseThrow(() -> new AssertionError("no candidate labelled " + label));
    }

    private static List<CompletionItem> items(StoreHandle handle, String buffer, Point cursor) {
        var parser = new Parser();
        parser.setLanguage(GraphqlLanguage.get());
        var bytes = buffer.getBytes(StandardCharsets.UTF_8);
        var tree = parser.parse(buffer).orElseThrow();
        var directive = Directives.findContaining(tree.getRootNode(), cursor)
            .orElseThrow(() -> new AssertionError("expected directive at cursor"));
        return VOCAB.locateAt(directive, cursor, bytes)
            .map(location -> ReferenceCompletions.generate(VOCAB, handle,
                CompletionContext.from(location, bytes), directive, bytes))
            .orElseGet(List::of);
    }
}
