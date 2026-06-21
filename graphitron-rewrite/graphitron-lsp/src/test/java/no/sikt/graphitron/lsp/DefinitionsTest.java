package no.sikt.graphitron.lsp;

import no.sikt.graphitron.lsp.definition.DefinitionTarget;
import no.sikt.graphitron.lsp.definition.Definitions;
import no.sikt.graphitron.lsp.state.WorkspaceFile;
import no.sikt.graphitron.rewrite.catalog.CompletionData;
import no.sikt.graphitron.rewrite.catalog.LspSchemaSnapshot;
import no.sikt.graphitron.rewrite.catalog.SourceWalker;
import no.sikt.graphitron.rewrite.catalog.TypeClassification;
import org.junit.jupiter.api.Test;
import io.github.treesitter.jtreesitter.Point;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Goto-definition for known directive arguments. Maps the cursor to a
 * {@code Location} on the consumer's Java tree pre-populated by
 * {@code CatalogBuilder}; the LSP just looks the location up.
 *
 * <p>Two families are covered: the jOOQ half ({@code @table} / {@code @field}
 * / {@code @reference}) and its fall-throughs (unknown name, unknown table,
 * unknown nested field, missing source location), and the service half (the
 * class-name / method-name binding directives), whose locations the
 * {@code SourceWalker} fills in from the consumer's sources. The catalog
 * fixtures here carry explicit source locations; real source-line refinement
 * is exercised at the pipeline tier in {@code CatalogBuilderSourceTest}.
 */
class DefinitionsTest {

    private static final String FILM_URI = "file:///fake/jooq/Film.java";
    private static final String LANGUAGE_URI = "file:///fake/jooq/Language.java";
    private static final String KEYS_URI = "file:///fake/jooq/Keys.java";

    @Test
    void tableDefinitionMapsToTableSourceUri() {
        var file = file("type Foo @table(name: \"film\") { bar: Int }");
        var pos = pointAt(file, 0, "film");

        var loc = Definitions.compute(file, filmCatalog(), SourceWalker.Index.EMPTY, LspSchemaSnapshot.unavailable(), pos).orElseThrow();
        assertThat(loc.getUri()).isEqualTo(FILM_URI);
        assertThat(loc.getRange().getStart().getLine()).isZero();
    }

    @Test
    void unknownTableReturnsEmpty() {
        var file = file("type Foo @table(name: \"GHOST\") { bar: Int }");
        var pos = pointAt(file, 0, "GHOST");
        assertThat(Definitions.compute(file, filmCatalog(), SourceWalker.Index.EMPTY, LspSchemaSnapshot.unavailable(), pos)).isEmpty();
    }

    @Test
    void fieldDefinitionMapsToTableSourceUri() {
        var file = file("""
            type Foo @table(name: "film") {
                bar: Int @field(name: "title")
            }
            """);
        var pos = pointAt(file, 1, "title");

        var loc = Definitions.compute(file, filmCatalog(), SourceWalker.Index.EMPTY, fooFilmSnapshot(), pos).orElseThrow();
        // Phase 4 sends columns to the same file as the owning table; line
        // refinement waits for JavaParser.
        assertThat(loc.getUri()).isEqualTo(FILM_URI);
    }

    @Test
    void referenceKeyMapsToKeysSourceUri() {
        var file = file("""
            type Foo @table(name: "film") {
                bar: Int @reference(path: [{key: "FILM__FILM_LANGUAGE_ID_FKEY"}])
            }
            """);
        var pos = pointAt(file, 1, "FILM__FILM_LANGUAGE_ID_FKEY");

        var loc = Definitions.compute(file, filmCatalog(), SourceWalker.Index.EMPTY, LspSchemaSnapshot.unavailable(), pos).orElseThrow();
        assertThat(loc.getUri()).isEqualTo(KEYS_URI);
    }

    @Test
    void referenceTableMapsToTargetTableUri() {
        var file = file("""
            type Foo @table(name: "film") {
                bar: Int @reference(path: [{table: "language"}])
            }
            """);
        var pos = pointAt(file, 1, "language");

        var loc = Definitions.compute(file, filmCatalog(), SourceWalker.Index.EMPTY, LspSchemaSnapshot.unavailable(), pos).orElseThrow();
        assertThat(loc.getUri()).isEqualTo(LANGUAGE_URI);
    }

    @Test
    void cursorOnDirectiveNameReturnsEmpty() {
        var file = file("type Foo @table(name: \"film\") { bar: Int }");
        // Cursor on the @table directive name token, not on its argument.
        int col = "type Foo @t".length();
        assertThat(Definitions.compute(file, filmCatalog(), SourceWalker.Index.EMPTY, LspSchemaSnapshot.unavailable(), new Point(0, col))).isEmpty();
    }

    @Test
    void unknownColumnReturnsEmpty() {
        var file = file("""
            type Foo @table(name: "film") {
                bar: Int @field(name: "GHOST")
            }
            """);
        var pos = pointAt(file, 1, "GHOST");
        assertThat(Definitions.compute(file, filmCatalog(), SourceWalker.Index.EMPTY, fooFilmSnapshot(), pos)).isEmpty();
    }

    @Test
    void unknownLocationProducesEmpty() {
        // Same shape as filmCatalog but without source URIs; every
        // location collapses to UNKNOWN, so goto-def returns empty.
        var unsourcedCatalog = new CompletionData(
            List.of(new CompletionData.Table(
                "film", "", CompletionData.SourceLocation.UNKNOWN,
                List.of(CompletionData.Column.of("title", "String", false, "")),
                List.of()
            )),
            List.of(),
            List.of()
        );
        var file = file("type Foo @table(name: \"film\") { bar: Int }");
        var pos = pointAt(file, 0, "film");
        assertThat(Definitions.compute(file, unsourcedCatalog, SourceWalker.Index.EMPTY, LspSchemaSnapshot.unavailable(), pos)).isEmpty();
    }

    // ---- Service half: class-name / method-name binding directives ----

    private static final String SVC_URI = "file:///fake/svc/PriceService.java";

    @Test
    void serviceClassNameJumpsToClassDeclaration() {
        var file = file("""
            type Query {
                films: Int @service(service: {className: "com.example.PriceService", method: "price"})
            }
            """);
        var pos = pointAt(file, 1, "com.example.PriceService");
        var loc = Definitions.compute(file, serviceCatalog(), serviceIndex(), LspSchemaSnapshot.unavailable(), pos).orElseThrow();
        assertThat(loc.getUri()).isEqualTo(SVC_URI);
        assertThat(loc.getRange().getStart().getLine()).isEqualTo(10);
    }

    @Test
    void serviceMethodJumpsToMethodDeclaration() {
        var file = file("""
            type Query {
                films: Int @service(service: {className: "com.example.PriceService", method: "price"})
            }
            """);
        var pos = pointAt(file, 1, "price");
        var loc = Definitions.compute(file, serviceCatalog(), serviceIndex(), LspSchemaSnapshot.unavailable(), pos).orElseThrow();
        assertThat(loc.getRange().getStart().getLine()).isEqualTo(12);
    }

    @Test
    void externalFieldMethodJumpsToMethodDeclaration() {
        var file = file("""
            type Foo {
                bar: Int @externalField(reference: {className: "com.example.PriceService", method: "price"})
            }
            """);
        var pos = pointAt(file, 1, "price");
        var loc = Definitions.compute(file, serviceCatalog(), serviceIndex(), LspSchemaSnapshot.unavailable(), pos).orElseThrow();
        assertThat(loc.getRange().getStart().getLine()).isEqualTo(12);
    }

    @Test
    void enumReferenceClassNameJumpsToClassDeclaration() {
        var file = file("""
            enum Color @enum(enumReference: {className: "com.example.PriceService"}) { RED }
            """);
        var pos = pointAt(file, 0, "com.example.PriceService");
        var loc = Definitions.compute(file, serviceCatalog(), serviceIndex(), LspSchemaSnapshot.unavailable(), pos).orElseThrow();
        assertThat(loc.getUri()).isEqualTo(SVC_URI);
    }

    @Test
    void conditionMethodJumpsToMethodDeclaration() {
        var file = file("""
            type Foo {
                bar: Int @condition(condition: {className: "com.example.PriceService", method: "price"})
            }
            """);
        var pos = pointAt(file, 1, "price");
        var loc = Definitions.compute(file, serviceCatalog(), serviceIndex(), LspSchemaSnapshot.unavailable(), pos).orElseThrow();
        assertThat(loc.getRange().getStart().getLine()).isEqualTo(12);
    }

    @Test
    void sourceRowFlatClassNameJumpsToClassDeclaration() {
        var file = file("""
            type Foo {
                bar: Int @sourceRow(className: "com.example.PriceService", method: "price")
            }
            """);
        var pos = pointAt(file, 1, "com.example.PriceService");
        var loc = Definitions.compute(file, serviceCatalog(), serviceIndex(), LspSchemaSnapshot.unavailable(), pos).orElseThrow();
        assertThat(loc.getUri()).isEqualTo(SVC_URI);
    }

    @Test
    void tableMethodFlatMethodJumpsToMethodDeclaration() {
        var file = file("""
            type Foo {
                bar: Int @tableMethod(className: "com.example.PriceService", method: "price")
            }
            """);
        var pos = pointAt(file, 1, "price");
        var loc = Definitions.compute(file, serviceCatalog(), serviceIndex(), LspSchemaSnapshot.unavailable(), pos).orElseThrow();
        assertThat(loc.getRange().getStart().getLine()).isEqualTo(12);
    }

    @Test
    void recordClassNameReturnsEmptyByCarveOut() {
        // @record is deprecated/ignored; its className binds no class even
        // though the coordinate is shared with @enum.
        var file = file("""
            type Foo @record(record: {className: "com.example.PriceService"}) { bar: Int }
            """);
        var pos = pointAt(file, 0, "com.example.PriceService");
        assertThat(Definitions.compute(file, serviceCatalog(), serviceIndex(), LspSchemaSnapshot.unavailable(), pos)).isEmpty();
    }

    @Test
    void unknownClassNameReturnsEmpty() {
        var file = file("""
            type Foo {
                bar: Int @service(service: {className: "com.example.Ghost", method: "price"})
            }
            """);
        var pos = pointAt(file, 1, "com.example.Ghost");
        assertThat(Definitions.compute(file, serviceCatalog(), serviceIndex(), LspSchemaSnapshot.unavailable(), pos)).isEmpty();
    }

    @Test
    void methodWithUnknownLocationReturnsEmpty() {
        // The overload-ambiguous method (Ambiguous arm) yields no jump.
        var file = file("""
            type Foo {
                bar: Int @service(service: {className: "com.example.PriceService", method: "ambiguous"})
            }
            """);
        var pos = pointAt(file, 1, "ambiguous");
        assertThat(Definitions.compute(file, serviceCatalog(), serviceIndex(), LspSchemaSnapshot.unavailable(), pos)).isEmpty();
    }

    // ---- Typed outcome (DefinitionTarget): each arm reachable ----

    @Test
    void classTargetLocatedWhenSourceIndexed() {
        assertThat(Definitions.classTarget(SVC_FQN, serviceIndex()))
            .isInstanceOf(DefinitionTarget.Located.class);
    }

    @Test
    void classTargetSourceAbsentWhenNotIndexed() {
        // Known reference (caller guards that), but no source walked for it: the
        // recoverable "source exists but isn't on a watched root" case lands here.
        assertThat(Definitions.classTarget(SVC_FQN, SourceWalker.Index.EMPTY))
            .isInstanceOf(DefinitionTarget.SourceAbsent.class);
    }

    @Test
    void methodTargetLocatedWhenSourceIndexed() {
        assertThat(Definitions.methodTarget(SVC_FQN, "price", serviceCatalog(), serviceIndex()))
            .isInstanceOf(DefinitionTarget.Located.class);
    }

    @Test
    void methodTargetAmbiguousWhenOverloadCollision() {
        assertThat(Definitions.methodTarget(SVC_FQN, "ambiguous", serviceCatalog(), serviceIndex()))
            .isInstanceOf(DefinitionTarget.Ambiguous.class);
    }

    @Test
    void methodTargetSourceAbsentWhenNotIndexed() {
        assertThat(Definitions.methodTarget(SVC_FQN, "price", serviceCatalog(), SourceWalker.Index.EMPTY))
            .isInstanceOf(DefinitionTarget.SourceAbsent.class);
    }

    private static final String SVC_FQN = "com.example.PriceService";

    /**
     * Catalog carries the bytecode-derived reference structure only; positions
     * live in the source index. {@code price} resolves; {@code ambiguous} is an
     * overload collision the source index dropped.
     */
    private static CompletionData serviceCatalog() {
        var price = new CompletionData.Method("price", "Field", "", List.of());
        var ambiguous = new CompletionData.Method("ambiguous", "Object", "", List.of());
        var ref = new CompletionData.ExternalReference(
            SVC_FQN, SVC_FQN, "", List.of(price, ambiguous), List.of());
        return new CompletionData(List.of(), List.of(), List.of(ref));
    }

    /**
     * The LSP-owned source-position index the service-half join reads. The
     * class and {@code price} resolve to declarations; {@code ambiguous} sits in
     * {@link SourceWalker.Index#ambiguousMethods()} (key dropped from
     * {@code methods}), driving the {@link DefinitionTarget.Ambiguous} arm.
     */
    private static SourceWalker.Index serviceIndex() {
        var classDecl = new SourceWalker.Decl(new CompletionData.SourceLocation(SVC_URI, 10, 4), "");
        var priceDecl = new SourceWalker.Decl(new CompletionData.SourceLocation(SVC_URI, 12, 4), "");
        return new SourceWalker.Index(
            Map.of(SVC_FQN, classDecl),
            Map.of(new SourceWalker.MethodKey(SVC_FQN, "price", 0), priceDecl),
            Map.of(),
            Set.of(new SourceWalker.MethodKey(SVC_FQN, "ambiguous", 0)));
    }

    private static LspSchemaSnapshot fooFilmSnapshot() {
        return new LspSchemaSnapshot.Built.Current(
            List.of(), Map.of(), Map.of(),
            Map.of(), Map.of("Foo", new TypeClassification.Table("film")));
    }

    private static Point pointAt(WorkspaceFile file, int line, String token) {
        String source = new String(file.source(), java.nio.charset.StandardCharsets.UTF_8);
        var lines = source.split("\n");
        int col = lines[line].indexOf(token);
        if (col < 0) {
            throw new AssertionError("token '" + token + "' not on line " + line + ": " + lines[line]);
        }
        return new Point(line, col + Math.max(1, token.length() / 2));
    }

    private static WorkspaceFile file(String source) {
        return new WorkspaceFile(1, source);
    }

    private static CompletionData filmCatalog() {
        var filmDef = new CompletionData.SourceLocation(FILM_URI, 0, 0);
        var langDef = new CompletionData.SourceLocation(LANGUAGE_URI, 0, 0);
        var keysDef = new CompletionData.SourceLocation(KEYS_URI, 0, 0);

        var film = new CompletionData.Table(
            "film", "", filmDef,
            List.of(
                new CompletionData.Column("film_id", "Integer", false, "", filmDef),
                new CompletionData.Column("title", "String", false, "", filmDef)
            ),
            List.of(
                new CompletionData.Reference("language", "FILM__FILM_LANGUAGE_ID_FKEY", false, keysDef)
            )
        );
        var language = new CompletionData.Table(
            "language", "", langDef,
            List.of(),
            List.of()
        );
        return new CompletionData(List.of(film, language), List.of(), List.of());
    }
}
