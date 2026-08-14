package no.sikt.graphitron.lsp;

import no.sikt.graphitron.lsp.definition.DefinitionTarget;
import no.sikt.graphitron.lsp.definition.Definitions;
import no.sikt.graphitron.lsp.parsing.LspVocabulary;
import no.sikt.graphitron.lsp.state.FileSnapshot;
import no.sikt.graphitron.lsp.state.WorkspaceFileTestSupport;
import no.sikt.graphitron.rewrite.catalog.CompletionData;
import no.sikt.graphitron.rewrite.catalog.LspSchemaSnapshot;
import no.sikt.graphitron.rewrite.catalog.TypeClassification;
import org.eclipse.lsp4j.Location;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import io.github.treesitter.jtreesitter.Point;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Goto-definition for known directive arguments: the cursor maps to a {@code Location} on a
 * declaration in the consumer's Java tree.
 *
 * <p>Two families are covered: the jOOQ half ({@code @table} / {@code @field} / {@code @reference})
 * and its fall-throughs (unknown name, unknown table, unknown nested field, source not parsed), and
 * the service half (the class-name / method-name binding directives). Both resolve positions from the
 * fact store's java-source family, joined by the FQNs the catalog carries; the catalog itself holds no
 * source positions.
 *
 * <p>The sources below are the fixture, written to disk and parsed for real, so every line number
 * asserted here is a parse's answer rather than a hand-built substrate's. The catalog stays a
 * hand-built projection: it is the classpath census half, saying which names are references at all,
 * which is what keeps an unknown name an empty answer rather than a no-position one.
 */
class DefinitionsTest {

    /** The sources' root, and a second store whose catalog was captured but whose sources never were. */
    @TempDir
    static Path sourceRoot;
    @TempDir
    static Path bareRoot;

    private static StoreFixture store;
    private static StoreFixture bare;

    /** The schema is beside the point in every case here; the subject is the {@code .java} files. */
    private static final String PLACEHOLDER_SDL = "type Query { placeholder: Int }\n";

    private static final String FILM_FQN = "fake.jooq.tables.Film";
    private static final String LANGUAGE_FQN = "fake.jooq.tables.Language";
    private static final String KEYS_FQN = "fake.jooq.Keys";
    private static final String SVC_FQN = "com.example.PriceService";

    // 0-based lines, as LSP counts them, in the sources written below; line 0 is the package
    // declaration every one of them opens with.
    private static final int CLASS_LINE = 1;
    private static final int TITLE_LINE = 3;
    private static final int FK_LINE = 2;
    private static final int PRICE_LINE = 2;
    private static final int SHIFTED_LINE = 3;
    private static final int PICK_TWO_ARG_LINE = 5;

    @BeforeAll
    static void parseSources() {
        store = StoreFixture.of(sourceRoot, PLACEHOLDER_SDL);
        bare = StoreFixture.of(bareRoot, PLACEHOLDER_SDL);
        store.withJavaSource(sourceRoot, FILM_FQN, """
            public class Film {
                public final Object film_id = null;
                public final Object title = null;
            }
            """);
        store.withJavaSource(sourceRoot, LANGUAGE_FQN, """
            public class Language {
            }
            """);
        store.withJavaSource(sourceRoot, KEYS_FQN, """
            public class Keys {
                public static final Object FILM__FILM_LANGUAGE_ID_FKEY = null;
            }
            """);
        // One method per line, so each line number above names one declaration. "shifted" is
        // declared at an arity the census does not carry, and "pick" is overloaded across two.
        store.withJavaSource(sourceRoot, SVC_FQN, """
            public class PriceService {
                public Object price() { return null; }
                public Object shifted(Object a) { return null; }
                public Object pick(Object a) { return null; }
                public Object pick(Object a, Object b) { return null; }
            }
            """);
    }

    @AfterAll
    static void closeStores() {
        store.close();
        bare.close();
    }

    @Test
    void tableDefinitionMapsToTableSourceUri() {
        var file = file("type Foo @table(name: \"film\") { bar: Int }");
        var pos = pointAt(file, 0, "film");

        var loc = compute(file, LspSchemaSnapshot.unavailable(), pos).orElseThrow();
        assertThat(loc.getUri()).endsWith("Film.java");
        assertThat(loc.getRange().getStart().getLine()).isEqualTo(CLASS_LINE);
    }

    @Test
    void unknownTableReturnsEmpty() {
        var file = file("type Foo @table(name: \"GHOST\") { bar: Int }");
        var pos = pointAt(file, 0, "GHOST");
        assertThat(compute(file, LspSchemaSnapshot.unavailable(), pos)).isEmpty();
    }

    @Test
    void fieldDefinitionMapsToColumnSourcePosition() {
        var file = file("""
            type Foo @table(name: "film") {
                bar: Int @field(name: "title")
            }
            """);
        var pos = pointAt(file, 1, "title");

        var loc = compute(file, fooFilmSnapshot(), pos).orElseThrow();
        assertThat(loc.getUri()).endsWith("Film.java");
        // The column's own declaration, not the class's.
        assertThat(loc.getRange().getStart().getLine()).isEqualTo(TITLE_LINE);
    }

    @Test
    void referenceKeyMapsToKeysSourceUri() {
        var file = file("""
            type Foo @table(name: "film") {
                bar: Int @reference(path: [{key: "FILM__FILM_LANGUAGE_ID_FKEY"}])
            }
            """);
        var pos = pointAt(file, 1, "FILM__FILM_LANGUAGE_ID_FKEY");

        var loc = compute(file, LspSchemaSnapshot.unavailable(), pos).orElseThrow();
        assertThat(loc.getUri()).endsWith("Keys.java");
        assertThat(loc.getRange().getStart().getLine()).isEqualTo(FK_LINE);
    }

    @Test
    void referenceTableMapsToTargetTableUri() {
        var file = file("""
            type Foo @table(name: "film") {
                bar: Int @reference(path: [{table: "language"}])
            }
            """);
        var pos = pointAt(file, 1, "language");

        var loc = compute(file, LspSchemaSnapshot.unavailable(), pos).orElseThrow();
        assertThat(loc.getUri()).endsWith("Language.java");
    }

    @Test
    void cursorOnDirectiveNameReturnsEmpty() {
        var file = file("type Foo @table(name: \"film\") { bar: Int }");
        // Cursor on the @table directive name token, not on its argument.
        int col = "type Foo @t".length();
        assertThat(compute(file, LspSchemaSnapshot.unavailable(), new Point(0, col))).isEmpty();
    }

    @Test
    void unknownColumnReturnsEmpty() {
        var file = file("""
            type Foo @table(name: "film") {
                bar: Int @field(name: "GHOST")
            }
            """);
        var pos = pointAt(file, 1, "GHOST");
        assertThat(compute(file, fooFilmSnapshot(), pos)).isEmpty();
    }

    @Test
    void tableKnownButSourceNotParsedProducesEmpty() {
        // A known table whose generated source no walk has parsed: the jOOQ half lands on the
        // SourceAbsent arm (a non-jump), the same way the service half does, rather than
        // synthesising a file-head jump.
        var file = file("type Foo @table(name: \"film\") { bar: Int }");
        var pos = pointAt(file, 0, "film");
        assertThat(Definitions.compute(
            file, catalog(), bare.handle(), LspSchemaSnapshot.unavailable(), pos)).isEmpty();
    }

    @Test
    void aSessionWithNoStoreAccessJumpsNowhere() {
        // Every arm here ends in a declaration position, and the store is where those live, so a
        // language server nobody handed store access to declines once rather than per arm.
        var file = file("type Foo @table(name: \"film\") { bar: Int }");
        var pos = pointAt(file, 0, "film");
        assertThat(Definitions.compute(LspVocabulary.load(), file, catalog(),
            Optional.empty(), LspSchemaSnapshot.unavailable(), pos)).isEmpty();
    }

    // ---- Service half: class-name / method-name binding directives ----

    @Test
    void serviceClassNameJumpsToClassDeclaration() {
        var file = file("""
            type Query {
                films: Int @service(service: {className: "com.example.PriceService", method: "price"})
            }
            """);
        var pos = pointAt(file, 1, "com.example.PriceService");
        var loc = compute(file, LspSchemaSnapshot.unavailable(), pos).orElseThrow();
        assertThat(loc.getUri()).endsWith("PriceService.java");
        assertThat(loc.getRange().getStart().getLine()).isEqualTo(CLASS_LINE);
    }

    @Test
    void serviceMethodJumpsToMethodDeclaration() {
        var file = file("""
            type Query {
                films: Int @service(service: {className: "com.example.PriceService", method: "price"})
            }
            """);
        var pos = pointAt(file, 1, "price");
        var loc = compute(file, LspSchemaSnapshot.unavailable(), pos).orElseThrow();
        assertThat(loc.getRange().getStart().getLine()).isEqualTo(PRICE_LINE);
    }

    @Test
    void externalFieldMethodJumpsToMethodDeclaration() {
        var file = file("""
            type Foo {
                bar: Int @externalField(reference: {className: "com.example.PriceService", method: "price"})
            }
            """);
        var pos = pointAt(file, 1, "price");
        var loc = compute(file, LspSchemaSnapshot.unavailable(), pos).orElseThrow();
        assertThat(loc.getRange().getStart().getLine()).isEqualTo(PRICE_LINE);
    }

    @Test
    void enumReferenceClassNameJumpsToClassDeclaration() {
        var file = file("""
            enum Color @enum(enumReference: {className: "com.example.PriceService"}) { RED }
            """);
        var pos = pointAt(file, 0, "com.example.PriceService");
        var loc = compute(file, LspSchemaSnapshot.unavailable(), pos).orElseThrow();
        assertThat(loc.getUri()).endsWith("PriceService.java");
    }

    @Test
    void conditionMethodJumpsToMethodDeclaration() {
        var file = file("""
            type Foo {
                bar: Int @condition(condition: {className: "com.example.PriceService", method: "price"})
            }
            """);
        var pos = pointAt(file, 1, "price");
        var loc = compute(file, LspSchemaSnapshot.unavailable(), pos).orElseThrow();
        assertThat(loc.getRange().getStart().getLine()).isEqualTo(PRICE_LINE);
    }

    @Test
    void sourceRowFlatClassNameJumpsToClassDeclaration() {
        var file = file("""
            type Foo {
                bar: Int @sourceRow(className: "com.example.PriceService", method: "price")
            }
            """);
        var pos = pointAt(file, 1, "com.example.PriceService");
        var loc = compute(file, LspSchemaSnapshot.unavailable(), pos).orElseThrow();
        assertThat(loc.getUri()).endsWith("PriceService.java");
    }

    @Test
    void recordClassNameReturnsEmptyByCarveOut() {
        // @record is deprecated/ignored; its className binds no class even though the coordinate is
        // shared with @enum.
        var file = file("""
            type Foo @record(record: {className: "com.example.PriceService"}) { bar: Int }
            """);
        var pos = pointAt(file, 0, "com.example.PriceService");
        assertThat(compute(file, LspSchemaSnapshot.unavailable(), pos)).isEmpty();
    }

    @Test
    void unknownClassNameReturnsEmpty() {
        var file = file("""
            type Foo {
                bar: Int @service(service: {className: "com.example.Ghost", method: "price"})
            }
            """);
        var pos = pointAt(file, 1, "com.example.Ghost");
        assertThat(compute(file, LspSchemaSnapshot.unavailable(), pos)).isEmpty();
    }

    @Test
    void undeclaredCensusArityFallsBackToTheDeclarationOfTheName() {
        // The census carries "shifted" at no arguments; the source declares it with one. Jumping to
        // the declaration of the name beats declining over an arity disagreement.
        var file = file("""
            type Foo {
                bar: Int @service(service: {className: "com.example.PriceService", method: "shifted"})
            }
            """);
        var pos = pointAt(file, 1, "shifted");
        var loc = compute(file, LspSchemaSnapshot.unavailable(), pos).orElseThrow();
        assertThat(loc.getRange().getStart().getLine()).isEqualTo(SHIFTED_LINE);
    }

    // ---- Typed outcome (DefinitionTarget): each arm reachable ----

    @Test
    void classTargetLocatedWhenSourceParsed() {
        assertThat(Definitions.classTarget(SVC_FQN, store.handle()))
            .isInstanceOf(DefinitionTarget.Located.class);
    }

    @Test
    void classTargetSourceAbsentWhenNotParsed() {
        // Known reference (caller guards that), but no source parsed for it: the recoverable "source
        // exists but isn't on a watched root" case lands here.
        assertThat(Definitions.classTarget(SVC_FQN, bare.handle()))
            .isInstanceOf(DefinitionTarget.SourceAbsent.class);
    }

    @Test
    void methodTargetLocatedWhenSourceParsed() {
        assertThat(Definitions.methodTarget(SVC_FQN, "price", catalog(), store.handle()))
            .isInstanceOf(DefinitionTarget.Located.class);
    }

    @Test
    void methodTargetPrefersACensusArityTheSourceDeclares() {
        // The census carries "pick" at no arguments and at two; the source declares one and two. A
        // fallback consulted per census arity would answer the first (no-argument) entry with the
        // one-argument declaration, so the arities are all tried before any fallback.
        var target = Definitions.methodTarget(SVC_FQN, "pick", catalog(), store.handle());
        assertThat(target).isInstanceOf(DefinitionTarget.Located.class);
        assertThat(((DefinitionTarget.Located) target).location().getRange().getStart().getLine())
            .isEqualTo(PICK_TWO_ARG_LINE);
    }

    @Test
    void methodTargetSourceAbsentWhenNotParsed() {
        assertThat(Definitions.methodTarget(SVC_FQN, "price", catalog(), bare.handle()))
            .isInstanceOf(DefinitionTarget.SourceAbsent.class);
    }

    private static Optional<Location> compute(
        FileSnapshot file, LspSchemaSnapshot snapshot, Point pos
    ) {
        return Definitions.compute(file, catalog(), store.handle(), snapshot, pos);
    }

    /**
     * The classpath census half: the jOOQ-derived structure plus the generated class FQNs (table
     * {@code classFqn}, {@code Keys} FQN on the reference) and the service's method signatures.
     * Positions live in the store, joined by those FQNs at request time.
     */
    private static CompletionData catalog() {
        var film = new CompletionData.Table(
            "film", "", FILM_FQN,
            List.of(
                new CompletionData.Column("film_id", "Integer", false, ""),
                new CompletionData.Column("title", "String", false, "")
            ),
            List.of(
                new CompletionData.Reference("language", "FILM__FILM_LANGUAGE_ID_FKEY", false, KEYS_FQN)
            )
        );
        var language = new CompletionData.Table("language", "", LANGUAGE_FQN, List.of(), List.of());
        var twoArgs = List.of(
            new CompletionData.Parameter("a", "Object", "", ""),
            new CompletionData.Parameter("b", "Object", "", ""));
        var service = new CompletionData.ExternalReference(
            SVC_FQN, SVC_FQN, "",
            List.of(
                new CompletionData.Method("price", "Field", "", List.of()),
                new CompletionData.Method("shifted", "Object", "", List.of()),
                new CompletionData.Method("pick", "Object", "", List.of()),
                new CompletionData.Method("pick", "Object", "", twoArgs)),
            List.of());
        return new CompletionData(List.of(film, language), List.of(), List.of(service));
    }

    private static LspSchemaSnapshot fooFilmSnapshot() {
        return new LspSchemaSnapshot.Built.Current(
            List.of(), Map.of(), Map.of(),
            Map.of(), Map.of("Foo", new TypeClassification.Table("film")));
    }

    private static Point pointAt(FileSnapshot file, int line, String token) {
        String source = new String(file.source(), java.nio.charset.StandardCharsets.UTF_8);
        var lines = source.split("\n");
        int col = lines[line].indexOf(token);
        if (col < 0) {
            throw new AssertionError("token '" + token + "' not on line " + line + ": " + lines[line]);
        }
        return new Point(line, col + Math.max(1, token.length() / 2));
    }

    private static FileSnapshot file(String source) {
        return WorkspaceFileTestSupport.snapshot(source);
    }
}
