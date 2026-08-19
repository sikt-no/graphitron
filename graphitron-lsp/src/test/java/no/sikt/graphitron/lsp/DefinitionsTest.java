package no.sikt.graphitron.lsp;

import no.sikt.graphitron.lsp.definition.DefinitionTarget;
import no.sikt.graphitron.lsp.definition.Definitions;
import no.sikt.graphitron.lsp.facts.ClasspathMethods;
import no.sikt.graphitron.lsp.parsing.LspVocabulary;
import no.sikt.graphitron.lsp.state.FileSnapshot;
import no.sikt.graphitron.lsp.state.WorkspaceFileTestSupport;
import no.sikt.graphitron.rewrite.catalog.CompletionData;
import org.eclipse.lsp4j.Location;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import io.github.treesitter.jtreesitter.Point;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Goto-definition for known directive arguments: the cursor maps to a {@code Location} on a
 * declaration in the consumer's Java tree.
 *
 * <p>Two families are covered: the jOOQ half ({@code @table} / {@code @field} / {@code @reference})
 * and its fall-throughs (unknown name, unknown table, unknown nested field, source not parsed), and
 * the service half (the class-name / method-name binding directives). Both resolve positions from the
 * fact store's java-source family, joined by the FQNs the census carries.
 *
 * <p>Both populations are captured rather than declared. The catalog is the fixture module's real
 * generated jOOQ model, so a table's class FQN, a column's generated field name and a key's
 * {@code Keys} constant are the values a consumer's editor would actually be jumping to, and the test
 * reads them back out of the census rather than spelling a naming strategy. The {@code .java} sources
 * are written to disk and parsed for real, so every line number asserted here is a parse's answer.
 * What the two have in common is only a name, which is the join the whole provider stands on and the
 * one thing a fixture must not fake.
 */
class DefinitionsTest {

    /** The sources' root, and a second store whose catalog was captured but whose sources never were. */
    @TempDir
    static Path sourceRoot;
    @TempDir
    static Path bareRoot;

    private static StoreFixture store;
    private static StoreFixture bare;

    /**
     * The captured schema. Mostly beside the point, the subject here being the {@code .java} files,
     * except for {@code Foo}: the column arm resolves the enclosing type's binding from the store, so
     * which table {@code Foo} is bound to is a captured fact and not something a buffer can assert.
     * That is the dev session's own shape, a capture from the last build under a buffer being edited,
     * and the buffers below name {@code Foo} because that is the type the store knows.
     */
    private static final String CAPTURED_SDL = """
        type Query { placeholder: Int }
        type Foo @table(name: "film") { bar: Int }
        """;

    private static final String SVC_FQN = "com.example.PriceService";

    /**
     * The one foreign key the {@code @reference(key:)} cases name, in both spellings the resolver
     * accepts: the generated constant and the SQL constraint name it was generated from. Named
     * literally rather than read back, because the point of the second case is that the two are
     * different strings and a reader cannot tell which one a lookup answered otherwise.
     */
    private static final String FK_CONSTANT = "FILM__FILM_LANGUAGE_ID_FKEY";
    private static final String FK_SQL_NAME = "film_language_id_fkey";

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
        store = StoreFixture.ofCatalog(sourceRoot, CAPTURED_SDL, census());
        bare = StoreFixture.ofCatalog(bareRoot, CAPTURED_SDL, census());
        // The generated classes the census points at, standing in for the real generated sources: the
        // join between the two populations is by name across two cadences, so a source that agrees on
        // the name is all it takes, and the column constants are the census's own spelling of them.
        store.withJavaSource(sourceRoot, store.tableClassFqn("film"), """
            public class Film {
                public final Object FILM_ID = null;
                public final Object TITLE = null;
            }
            """);
        store.withJavaSource(sourceRoot, store.tableClassFqn("language"), """
            public class Language {
            }
            """);
        store.withJavaSource(sourceRoot, store.keysClassFqn("film"), """
            public class Keys {
                public static final Object %s = null;
            }
            """.formatted(FK_CONSTANT));
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

        var loc = compute(file, pos).orElseThrow();
        assertThat(loc.getUri()).endsWith("Film.java");
        assertThat(loc.getRange().getStart().getLine()).isEqualTo(CLASS_LINE);
    }

    @Test
    void unknownTableReturnsEmpty() {
        var file = file("type Foo @table(name: \"MISSING\") { bar: Int }");
        var pos = pointAt(file, 0, "MISSING");
        assertThat(compute(file, pos)).isEmpty();
    }

    @Test
    void fieldDefinitionMapsToColumnSourcePosition() {
        var file = file("""
            type Foo @table(name: "film") {
                bar: Int @field(name: "title")
            }
            """);
        var pos = pointAt(file, 1, "title");

        var loc = compute(file, pos).orElseThrow();
        assertThat(loc.getUri()).endsWith("Film.java");
        // The column's own declaration, not the class's.
        assertThat(loc.getRange().getStart().getLine()).isEqualTo(TITLE_LINE);
    }

    @Test
    void referenceKeyMapsToKeysSourceUri() {
        var loc = keyDefinition(FK_CONSTANT).orElseThrow();
        assertThat(loc.getUri()).endsWith("Keys.java");
        assertThat(loc.getRange().getStart().getLine()).isEqualTo(FK_LINE);
    }

    @Test
    void referenceKeyResolvesUnderTheSqlConstraintName() {
        // The generator resolves either namespace, and the completion arm offers both, so the jump
        // lands on the same constant whichever one the author wrote.
        var loc = keyDefinition(FK_SQL_NAME).orElseThrow();
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

        var loc = compute(file, pos).orElseThrow();
        assertThat(loc.getUri()).endsWith("Language.java");
    }

    @Test
    void cursorOnDirectiveNameReturnsEmpty() {
        var file = file("type Foo @table(name: \"film\") { bar: Int }");
        // Cursor on the @table directive name token, not on its argument.
        int col = "type Foo @t".length();
        assertThat(compute(file, new Point(0, col))).isEmpty();
    }

    @Test
    void unknownColumnReturnsEmpty() {
        var file = file("""
            type Foo @table(name: "film") {
                bar: Int @field(name: "MISSING")
            }
            """);
        var pos = pointAt(file, 1, "MISSING");
        assertThat(compute(file, pos)).isEmpty();
    }

    @Test
    void tableKnownButSourceNotParsedProducesEmpty() {
        // A known table whose generated source no walk has parsed: the jOOQ half lands on the
        // SourceAbsent arm (a non-jump), the same way the service half does, rather than
        // synthesising a file-head jump.
        var file = file("type Foo @table(name: \"film\") { bar: Int }");
        var pos = pointAt(file, 0, "film");
        assertThat(Definitions.compute(file, bare.handle(), pos)).isEmpty();
    }

    @Test
    void aSessionWithNoStoreAccessJumpsNowhere() {
        // Every arm here ends in a declaration position, and the store is where those live, so a
        // language server nobody handed store access to declines once rather than per arm.
        var file = file("type Foo @table(name: \"film\") { bar: Int }");
        var pos = pointAt(file, 0, "film");
        assertThat(Definitions.compute(BundledVocabulary.get(), file, Optional.empty(), pos)).isEmpty();
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
        var loc = compute(file, pos).orElseThrow();
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
        var loc = compute(file, pos).orElseThrow();
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
        var loc = compute(file, pos).orElseThrow();
        assertThat(loc.getRange().getStart().getLine()).isEqualTo(PRICE_LINE);
    }

    @Test
    void enumReferenceClassNameJumpsToClassDeclaration() {
        var file = file("""
            enum Color @enum(enumReference: {className: "com.example.PriceService"}) { RED }
            """);
        var pos = pointAt(file, 0, "com.example.PriceService");
        var loc = compute(file, pos).orElseThrow();
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
        var loc = compute(file, pos).orElseThrow();
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
        var loc = compute(file, pos).orElseThrow();
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
        assertThat(compute(file, pos)).isEmpty();
    }

    @Test
    void unknownClassNameReturnsEmpty() {
        var file = file("""
            type Foo {
                bar: Int @service(service: {className: "com.example.Missing", method: "price"})
            }
            """);
        var pos = pointAt(file, 1, "com.example.Missing");
        assertThat(compute(file, pos)).isEmpty();
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
        var loc = compute(file, pos).orElseThrow();
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
        assertThat(methodTarget("price", store))
            .isInstanceOf(DefinitionTarget.Located.class);
    }

    @Test
    void methodTargetPrefersACensusArityTheSourceDeclares() {
        // The census carries "pick" at no arguments and at two; the source declares one and two. A
        // fallback consulted per census arity would answer the first (no-argument) entry with the
        // one-argument declaration, so the arities are all tried before any fallback.
        var target = methodTarget("pick", store);
        assertThat(target).isInstanceOf(DefinitionTarget.Located.class);
        assertThat(((DefinitionTarget.Located) target).location().getRange().getStart().getLine())
            .isEqualTo(PICK_TWO_ARG_LINE);
    }

    @Test
    void methodTargetSourceAbsentWhenNotParsed() {
        assertThat(methodTarget("price", bare))
            .isInstanceOf(DefinitionTarget.SourceAbsent.class);
    }

    /** The arm as its caller reaches it: the overload set read once, then joined to the sources. */
    private static DefinitionTarget methodTarget(String methodName, StoreFixture from) {
        List<ClasspathMethods.Method> overloads =
            ClasspathMethods.named(from.handle(), SVC_FQN, methodName);
        assertThat(overloads).as("census carries %s", methodName).isNotEmpty();
        return Definitions.methodTarget(SVC_FQN, methodName, overloads, from.handle());
    }

    private static Optional<Location> keyDefinition(String spelling) {
        var file = file("""
            type Foo @table(name: "film") {
                bar: Int @reference(path: [{key: "%s"}])
            }
            """.formatted(spelling));
        return compute(file, pointAt(file, 1, spelling));
    }

    private static Optional<Location> compute(FileSnapshot file, Point pos) {
        return Definitions.compute(file, store.handle(), pos);
    }

    /**
     * The classpath census half: one service class whose method signatures are what the arity join
     * reads. Positions live in the store's java-source family, joined by the class FQN at request
     * time.
     */
    private static List<CompletionData.ExternalReference> census() {
        return List.of(StoreFixture.jarClass(SVC_FQN, List.of(
            StoreFixture.method("price", "Field"),
            StoreFixture.method("shifted", "Object"),
            StoreFixture.method("pick", "Object"),
            StoreFixture.method("pick", "Object",
                StoreFixture.parameter("a", "Object"),
                StoreFixture.parameter("b", "Object")))));
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
