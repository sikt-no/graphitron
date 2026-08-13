package no.sikt.graphitron.lsp;

import io.github.treesitter.jtreesitter.Parser;
import io.github.treesitter.jtreesitter.Point;
import no.sikt.graphitron.lsp.completions.CompletionContext;
import no.sikt.graphitron.lsp.completions.ReferenceCompletions;
import no.sikt.graphitron.lsp.parsing.Directives;
import no.sikt.graphitron.lsp.parsing.GraphqlLanguage;
import no.sikt.graphitron.lsp.parsing.LspVocabulary;
import no.sikt.graphitron.model.read.StoreHandle;
import no.sikt.graphitron.rewrite.catalog.LspSchemaSnapshot;
import no.sikt.graphitron.rewrite.catalog.TypeClassification;
import org.eclipse.lsp4j.CompletionItem;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Coverage for {@code @reference(path: [{key: "..."}, {table: "..."}])}. The candidates are the
 * {@code sql_referential_constraint} rows touching the enclosing type's table, in either direction;
 * which table that is stays the snapshot's answer, so the fixtures pair a real captured catalog with
 * a hand-built type classification.
 *
 * <p>The census comes from the fixture modules' real generated jOOQ models, so a constraint name here
 * is one an author could actually have typed, and the multi-schema model supplies the two shapes a
 * single-schema one cannot: a name declared in two schemas, and a name declared twice inside one.
 */
class ReferenceCompletionsTest {

    private static final LspVocabulary VOCAB = LspVocabulary.load();

    private static final String SDL = "type Query { placeholder: Int }\n";

    @Test
    void keyCompletionReturnsForeignKeysTouchingTheEnclosingTable(@TempDir Path tmp) {
        String source = keySite("film");

        try (var fixture = StoreFixture.ofCatalog(tmp, SDL)) {
            var items = items(fixture, source, "film");

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
        String source = keySite("film");

        try (var fixture = StoreFixture.ofCatalog(tmp, SDL)) {
            assertThat(documentationOf(items(fixture, source, "film"), "film_language_id_fkey"))
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
        String source = keySite("category");

        try (var fixture = StoreFixture.ofCatalog(tmp, SDL)) {
            var items = items(fixture, source, "category");

            assertThat(labels(items))
                .filteredOn("category_parent_category_id_fkey"::equals).hasSize(1);
            assertThat(detailOf(items, "category_parent_category_id_fkey")).isEqualTo("→ category");
        }
    }

    /**
     * A constraint name two schemas both declare is offered once per schema, qualified. The qualifier
     * is grammar {@code key:} accepts and treats as stated intent, so each label resolves to exactly
     * the key it came from; a name only one schema declares stays bare, as the manual writes it.
     */
    @Test
    void aNameTwoSchemasDeclareIsOfferedQualified(@TempDir Path tmp) {
        String source = keySite("event");

        try (var fixture = StoreFixture.ofMultiSchemaCatalog(tmp, SDL)) {
            assertThat(labels(items(fixture, source, "event")))
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
        String source = keySite("gizmo");

        try (var fixture = StoreFixture.ofMultiSchemaCatalog(tmp, SDL)) {
            var items = items(fixture, source, "gizmo");

            assertThat(labels(items)).containsExactly("dup_gizmo_fk");
            assertThat(detailOf(items, "dup_gizmo_fk")).isEqualTo("← dup_one, ← dup_two");
        }
    }

    @Test
    void tableCompletionRoutesThroughTableCompletionsNotHere(@TempDir Path tmp) {
        // ReferenceCompletions narrows to the FK (CatalogFkBinding) arm. Table completion at
        // @reference(path: [{table:}]) is the ReferenceElement.table coordinate's
        // CatalogTableBinding, served by TableCompletions. ReferenceCompletions returns empty here.
        String source = """
            type Foo @table(name: "film") {
                bar: Int @reference(path: [{table: ""}])
            }
            """;

        try (var fixture = StoreFixture.ofCatalog(tmp, SDL)) {
            assertThat(items(fixture, source, "film")).isEmpty();
        }
    }

    @Test
    void cursorOutsideNestedValueReturnsEmpty(@TempDir Path tmp) {
        // Cursor on the nested field's key name, not on its value.
        String source = """
            type Foo @table(name: "film") {
                bar: Int @reference(path: [{key: "film_language_id_fkey"}])
            }
            """;
        var cursor = new Point(1, source.split("\n")[1].indexOf("key:") + 1);

        try (var fixture = StoreFixture.ofCatalog(tmp, SDL)) {
            assertThat(items(fixture.handle(), source, cursor, snapshotBinding("film"))).isEmpty();
        }
    }

    /**
     * The snapshot is the source of truth for the type-to-table binding. A type the classifier maps
     * to a table no capture recorded has no keys to offer, which the census answers by holding no
     * rows for it rather than by any lookup the arm has to make first.
     */
    @Test
    void unknownTableReturnsEmptyForKey(@TempDir Path tmp) {
        String source = keySite("missing");

        try (var fixture = StoreFixture.ofCatalog(tmp, SDL)) {
            assertThat(items(fixture, source, "missing")).isEmpty();
        }
    }

    @Test
    void unknownNestedFieldReturnsEmpty(@TempDir Path tmp) {
        // 'condition' is a real field on ReferenceElement but autocomplete for it is not plugged in;
        // the dispatcher returns empty.
        String source = """
            type Foo @table(name: "film") {
                bar: Int @reference(path: [{condition: ""}])
            }
            """;

        try (var fixture = StoreFixture.ofCatalog(tmp, SDL)) {
            assertThat(items(fixture, source, "film")).isEmpty();
        }
    }

    /** A key site whose enclosing type is bound to {@code tableName}, cursor inside the empty value. */
    private static String keySite(String tableName) {
        return """
            type Foo @table(name: "%s") {
                bar: Int @reference(path: [{key: ""}])
            }
            """.formatted(tableName);
    }

    private static List<CompletionItem> items(StoreFixture fixture, String source, String tableName) {
        var lines = source.split("\n");
        var cursor = new Point(1, lines[1].indexOf("\"\"") + 1);
        return items(fixture.handle(), source, cursor, snapshotBinding(tableName));
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

    private static List<CompletionItem> items(
        StoreHandle handle, String source, Point cursor, LspSchemaSnapshot snapshot
    ) {
        var parser = new Parser();
        parser.setLanguage(GraphqlLanguage.get());
        var bytes = source.getBytes(StandardCharsets.UTF_8);
        var tree = parser.parse(source).orElseThrow();
        var directive = Directives.findContaining(tree.getRootNode(), cursor)
            .orElseThrow(() -> new AssertionError("expected directive at cursor"));
        return VOCAB.locateAt(directive, cursor, bytes)
            .map(location -> ReferenceCompletions.generate(VOCAB, handle, snapshot,
                CompletionContext.from(location, bytes), directive, bytes))
            .orElseGet(List::of);
    }

    private static LspSchemaSnapshot snapshotBinding(String tableName) {
        return new LspSchemaSnapshot.Built.Current(
            List.of(), Map.of(), Map.of(),
            Map.of(), Map.of("Foo", new TypeClassification.Table(tableName)));
    }
}
