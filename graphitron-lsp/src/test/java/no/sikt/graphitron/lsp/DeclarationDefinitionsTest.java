package no.sikt.graphitron.lsp;

import io.github.treesitter.jtreesitter.Point;
import no.sikt.graphitron.lsp.definition.DeclarationDefinitions;
import no.sikt.graphitron.lsp.state.FileSnapshot;
import no.sikt.graphitron.lsp.state.WorkspaceFileTestSupport;
import no.sikt.graphitron.lsp.parsing.DeclTarget;
import no.sikt.graphitron.rewrite.catalog.CompletionData;
import no.sikt.graphitron.rewrite.catalog.FieldClassification;
import no.sikt.graphitron.rewrite.catalog.LspSchemaSnapshot;
import org.eclipse.lsp4j.Location;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Goto-definition from an SDL declaration name (a type name or a field / input-value name, not a
 * directive argument) to the Java the model bound it to. Dispatches on what the store says the
 * enclosing type's members resolve against and resolves each target against the fact store's
 * java-source family, joined by the census's structural keys, exactly like {@code Definitions} does
 * for the directive-argument half. Covers one case per scope per axis.
 *
 * <p>Every position asserted here came from parsing a real {@code .java} file written to disk: the
 * sources below are the fixture, and their line numbers are the expectations. That matters for a
 * provider whose whole job is a position, since a hand-built substrate can assert a declaration the
 * parse would not produce, which is how a record component's doc comment went unnoticed for a while.
 * The census is captured for the same reason, the fixture module's real generated catalog for the
 * table arms and a real class list for the rest: it says which names are references, and the parse
 * says where they are declared, and the join between the two is only ever a name.
 */
class DeclarationDefinitionsTest {

    /** The sources' root, and a second store whose catalog was captured but whose sources never were. */
    @TempDir
    static Path sourceRoot;
    @TempDir
    static Path bareRoot;

    private static StoreFixture store;
    private static StoreFixture bare;

    private static final String RECORD_FQN = "com.example.FilmDto";
    private static final String POJO_FQN = "com.example.FilmPojo";
    private static final String STD_FQN = "com.example.FilmRecord";
    private static final String SVC_FQN = "com.example.FilmService";

    /**
     * The captured graph, which is what says what each buffer's type resolves against: one
     * {@code @table} binding, and three types a producer of the census's own grounds on a class. The
     * subject of every case is still the {@code .java} files, but the scope a coordinate resolves in is
     * the store's answer now, so a schema saying so has to exist.
     */
    private static final String CAPTURED_SDL = """
        type Query {
            table: FilmTable
            dto: FilmRecord @service(service: {className: "%1$s", method: "makeDto"})
            pojo: FilmPojo @service(service: {className: "%1$s", method: "makePojo"})
            std: FilmStd @service(service: {className: "%1$s", method: "makeStd"})
        }
        type FilmTable @table(name: "film") { title: String }
        type FilmRecord { firstName: String }
        type FilmPojo { firstName: String }
        type FilmStd { value: String }
        """.formatted(SVC_FQN);

    // 0-based lines, as LSP counts them, in the sources written below; line 0 is the package
    // declaration every one of them opens with.
    private static final int TABLE_CLASS_LINE = 1;
    private static final int COLUMN_LINE = 2;
    private static final int COMPONENT_LINE = 2;
    private static final int ACCESSOR_LINE = 2;
    private static final int PRICE_LINE = 2;
    private static final int DISCOUNT_LINE = 3;
    private static final int COMPUTED_LINE = 4;
    private static final int ROUTINE_LINE = 5;
    private static final int GREET0_LINE = 6;
    private static final int GREET2_LINE = 7;
    private static final int TWIN_FIRST_LINE = 8;

    /** The generated table class the census recorded, read back rather than spelled out. */
    private static String filmFqn;

    @BeforeAll
    static void parseSources() {
        store = StoreFixture.ofCatalog(sourceRoot, CAPTURED_SDL, census());
        bare = StoreFixture.ofCatalog(bareRoot, CAPTURED_SDL, census());
        filmFqn = store.tableClassFqn("film");
        // The column constant is the census's own spelling of it, which is what the class declares
        // and what the resolution keys the position lookup by.
        store.withJavaSource(sourceRoot, filmFqn, """
            public class Film {
                public final Object TITLE = null;
            }
            """);
        store.withJavaSource(sourceRoot, RECORD_FQN, """
            public record FilmDto(
                String firstName
            ) {
            }
            """);
        store.withJavaSource(sourceRoot, POJO_FQN, """
            public class FilmPojo {
                public String getFirstName() { return null; }
            }
            """);
        store.withJavaSource(sourceRoot, STD_FQN, """
            public class FilmRecord {
            }
            """);
        // The service's methods are laid out one per line so each line number below names one
        // declaration; greet is arity-overloaded and twin is a same-arity pair.
        store.withJavaSource(sourceRoot, SVC_FQN, """
            public class FilmService {
                public Object price(Object ctx) { return null; }
                public Object discount(Object ctx) { return null; }
                public Object computeCol(Object ctx) { return null; }
                public Object viaMethod(Object ctx) { return null; }
                public Object greet() { return null; }
                public Object greet(Object a, Object b) { return null; }
                public Object twin(Object a) { return null; }
                public Object twin(String b) { return null; }
            }
            """);
    }

    @AfterAll
    static void closeStores() {
        store.close();
        bare.close();
    }

    // ---- Type name -> backing class ----

    @Test
    void typeNameOnTableJumpsToTableClass() {
        var file = file("type FilmTable @table(name: \"film\") { title: String }");
        var loc = compute(file, pointAt(file, 0, "FilmTable")).orElseThrow();
        assertThat(loc.getUri()).endsWith("Film.java");
        assertThat(loc.getRange().getStart().getLine()).isEqualTo(TABLE_CLASS_LINE);
    }

    @Test
    void typeNameOnRecordJumpsToBackingClass() {
        var file = file("type FilmRecord { firstName: String }");
        var loc = compute(file, pointAt(file, 0, "FilmRecord")).orElseThrow();
        assertThat(loc.getUri()).endsWith("FilmDto.java");
    }

    @Test
    void typeNameOnPojoJumpsToBackingClass() {
        var file = file("type FilmPojo { firstName: String }");
        var loc = compute(file, pointAt(file, 0, "FilmPojo")).orElseThrow();
        assertThat(loc.getUri()).endsWith("FilmPojo.java");
    }

    @Test
    void typeNameOnAClassWithNoMembersInTheCensusStillJumpsToIt() {
        var file = file("type FilmStd { value: String }");
        var loc = compute(file, pointAt(file, 0, "FilmStd")).orElseThrow();
        assertThat(loc.getUri()).endsWith("FilmRecord.java");
    }

    @Test
    void typeNameKnownButSourceNotParsedReturnsEmpty() {
        // A reflection-bound type whose backing class no walk has parsed: the SourceAbsent arm is a
        // non-jump, the same contract as the jOOQ half.
        var file = file("type FilmRecord { firstName: String }");
        assertThat(DeclarationDefinitions.compute(
            file, bare.handle(), snapshot(), pointAt(file, 0, "FilmRecord")))
            .isEmpty();
    }

    // ---- Field name -> backing member ----

    @Test
    void fieldNameOnTableColumnJumpsToColumn() {
        var file = file("type FilmTable @table(name: \"film\") { title: String }");
        var loc = compute(file, pointAt(file, 0, "title")).orElseThrow();
        assertThat(loc.getUri()).endsWith("Film.java");
        assertThat(loc.getRange().getStart().getLine()).isEqualTo(COLUMN_LINE);
    }

    @Test
    void fieldNameWithFieldDirectiveOverrideResolvesColumn() {
        // The bound column is named by @field(name:), not the SDL field name.
        var file = file("type FilmTable @table(name: \"film\") { renamed: String @field(name: \"title\") }");
        var loc = compute(file, pointAt(file, 0, "renamed")).orElseThrow();
        assertThat(loc.getUri()).endsWith("Film.java");
        assertThat(loc.getRange().getStart().getLine()).isEqualTo(COLUMN_LINE);
    }

    @Test
    void fieldNameOnPojoJumpsToAccessorMethod() {
        var file = file("type FilmPojo { firstName: String }");
        var loc = compute(file, pointAt(file, 0, "firstName")).orElseThrow();
        assertThat(loc.getUri()).endsWith("FilmPojo.java");
        assertThat(loc.getRange().getStart().getLine()).isEqualTo(ACCESSOR_LINE);
    }

    @Test
    void fieldNameOnRecordComponentJumpsToComponent() {
        // Member-precise: the parse reads a record component as a field declaration (the implicit
        // accessor is synthesised later), so the component name is its own field row.
        var file = file("type FilmRecord { firstName: String }");
        var loc = compute(file, pointAt(file, 0, "firstName")).orElseThrow();
        assertThat(loc.getUri()).endsWith("FilmDto.java");
        assertThat(loc.getRange().getStart().getLine()).isEqualTo(COMPONENT_LINE);
    }

    /**
     * The type is scoped to a class the census holds no members for, so a member name inside it names
     * no declaration. The incumbent projection jumped to the class itself here, that being the one
     * shape it held no member keys for; the class it jumped to had not been asked whether it declares
     * the member, so the jump was a guess dressed as a resolution.
     */
    @Test
    void fieldNameOnAClassWithNoMembersInTheCensusJumpsNowhere() {
        var file = file("type FilmStd { value: String }");
        assertThat(compute(file, pointAt(file, 0, "value"))).isEmpty();
    }

    @Test
    void fieldNameUnknownMemberReturnsEmpty() {
        var file = file("type FilmRecord { ghost: String }");
        assertThat(compute(file, pointAt(file, 0, "ghost"))).isEmpty();
    }

    // ---- Method-backed field name -> bound method ----

    @Test
    void rootServiceFieldNameJumpsToServiceMethod() {
        var file = file("type Query { price: Int }");
        var loc = compute(file, pointAt(file, 0, "price")).orElseThrow();
        assertThat(loc.getUri()).endsWith("FilmService.java");
        assertThat(loc.getRange().getStart().getLine()).isEqualTo(PRICE_LINE);
    }

    @Test
    void fieldLevelServiceFieldNameJumpsToServiceMethod() {
        // The field is bound to its @service method, not to a column on the parent table: the
        // classification takes precedence over the TableBacking.
        var file = file("type FilmTable @table(name: \"film\") { discount: Int }");
        var loc = compute(file, pointAt(file, 0, "discount")).orElseThrow();
        assertThat(loc.getUri()).endsWith("FilmService.java");
        assertThat(loc.getRange().getStart().getLine()).isEqualTo(DISCOUNT_LINE);
    }

    @Test
    void externalFieldNameJumpsToComputedMethod() {
        var file = file("type FilmTable @table(name: \"film\") { computed: Int }");
        var loc = compute(file, pointAt(file, 0, "computed")).orElseThrow();
        assertThat(loc.getRange().getStart().getLine()).isEqualTo(COMPUTED_LINE);
    }

    @Test
    void routineBackedFieldNameJumpsToMethod() {
        var file = file("type Query { viaMethod: Int }");
        var loc = compute(file, pointAt(file, 0, "viaMethod")).orElseThrow();
        assertThat(loc.getRange().getStart().getLine()).isEqualTo(ROUTINE_LINE);
    }

    @Test
    void arityDistinguishableOverloadResolvesToCorrectOverload() {
        // The bound arity selects the right overload; the precision is not lost to the name-level
        // fallback, which would land on the first declaration for both.
        assertThat(locate(new DeclTarget.SourceMethod(SVC_FQN, "greet", 0)).orElseThrow()
            .getRange().getStart().getLine()).isEqualTo(GREET0_LINE);
        assertThat(locate(new DeclTarget.SourceMethod(SVC_FQN, "greet", 2)).orElseThrow()
            .getRange().getStart().getLine()).isEqualTo(GREET2_LINE);
    }

    @Test
    void sameArityOverloadPairJumpsToTheFirstDeclaration() {
        // Two declarations of one arity are two rows under their own ordinals, so the arity tier
        // resolves rather than having to be abandoned; the first of them wins the slot.
        assertThat(locate(new DeclTarget.SourceMethod(SVC_FQN, "twin", 1)).orElseThrow()
            .getRange().getStart().getLine()).isEqualTo(TWIN_FIRST_LINE);
    }

    @Test
    void anUndeclaredArityFallsBackToTheDeclarationOfTheName() {
        // The census can name an arity the source does not declare (an overload it saw in bytecode
        // this source has since dropped); jumping to the name is better than declining.
        assertThat(locate(new DeclTarget.SourceMethod(SVC_FQN, "greet", 7)).orElseThrow()
            .getRange().getStart().getLine()).isEqualTo(GREET0_LINE);
    }

    @Test
    void methodBackedFieldKnownButSourceNotParsedReturnsEmpty() {
        // The classification names a method, but nothing has parsed a declaration for it: a non-jump,
        // the same contract as the other backing arms.
        var file = file("type Query { price: Int }");
        assertThat(DeclarationDefinitions.compute(
            file, bare.handle(), snapshot(), pointAt(file, 0, "price")))
            .isEmpty();
    }

    // ---- No backing / no trigger ----

    @Test
    void noBackingTypeNameReturnsEmpty() {
        var file = file("type Query { films: String }");
        assertThat(compute(file, pointAt(file, 0, "Query"))).isEmpty();
    }

    @Test
    void noBackingFieldNameReturnsEmpty() {
        var file = file("type Query { films: String }");
        assertThat(compute(file, pointAt(file, 0, "films"))).isEmpty();
    }

    @Test
    void cursorOnDirectiveArgumentIsNotADeclarationTrigger() {
        // "film" sits in @table(name:), not on a declaration name, so this provider declines (the
        // directive-arg half owns that coordinate).
        var file = file("type FilmTable @table(name: \"film\") { title: String }");
        assertThat(compute(file, pointAt(file, 0, "film"))).isEmpty();
    }

    @Test
    void unavailableSnapshotReturnsEmpty() {
        var file = file("type FilmRecord { firstName: String }");
        assertThat(DeclarationDefinitions.compute(
            file, store.handle(), LspSchemaSnapshot.unavailable(),
            pointAt(file, 0, "FilmRecord")))
            .isEmpty();
    }

    @Test
    void aSessionWithNoStoreAccessJumpsNowhere() {
        // The positions live in the store, so a language server nobody handed store access to has
        // nowhere to jump from, and says so once rather than per arm.
        var file = file("type FilmRecord { firstName: String }");
        assertThat(DeclarationDefinitions.compute(
            file, Optional.empty(), snapshot(), pointAt(file, 0, "FilmRecord")))
            .isEmpty();
    }

    private static Optional<Location> compute(FileSnapshot file, Point pos) {
        return DeclarationDefinitions.compute(file, store.handle(), snapshot(), pos);
    }

    private static Optional<Location> locate(DeclTarget target) {
        return DeclarationDefinitions.locate(target, store.handle());
    }

    /**
     * The classpath census half: which names are references, and with what members. The record's
     * component and the POJO's accessor are what a member name resolves through; the service's four
     * one-argument methods are the arities a method-backed field resolves at, and its three factories
     * are what ground the captured graph's three class-scoped types. {@code greet} and {@code twin} are
     * deliberately absent, so the cases that name them exercise the arity-0 fallback on a method the
     * census does not carry. Positions are the parse's answer, joined to this by name.
     *
     * <p>{@link #STD_FQN} is deliberately not a reference here. It is the class a type is grounded on
     * whose members the census does not hold, which is the population a generated jOOQ record falls
     * into: the type name still names the class, and a member name inside it names nothing.
     */
    private static List<CompletionData.ExternalReference> census() {
        var oneArg = StoreFixture.parameter("ctx", "DSLContext");
        return List.of(
            StoreFixture.jarRecord(RECORD_FQN, StoreFixture.component("firstName", "String")),
            StoreFixture.jarClass(POJO_FQN, List.of(StoreFixture.method("getFirstName", "String"))),
            StoreFixture.jarClass(SVC_FQN, List.of(
                StoreFixture.method("price", "Field", oneArg),
                StoreFixture.method("discount", "Field", oneArg),
                StoreFixture.method("computeCol", "Field", oneArg),
                StoreFixture.method("viaMethod", "Film", oneArg),
                StoreFixture.producing("makeDto", RECORD_FQN),
                StoreFixture.producing("makePojo", POJO_FQN),
                StoreFixture.producing("makeStd", STD_FQN))));
    }

    /**
     * The projection, carrying the one question the declaration-name resolution still puts to it:
     * which Java method a method-backed field binds to. No backing, that being the store's.
     */
    private static LspSchemaSnapshot snapshot() {
        // Method-backed field classifications, one per named variant. Each takes precedence over the
        // enclosing type's own scope in the field-name arm.
        Map<String, FieldClassification> classifications = Map.of(
            "Query.price", new FieldClassification.QueryService(SVC_FQN, "price", false, null, null),
            "FilmTable.discount", new FieldClassification.ServiceBacked(SVC_FQN, "discount", false, null, null),
            "FilmTable.computed", new FieldClassification.Computed(SVC_FQN, "computeCol"),
            "Query.viaMethod", new FieldClassification.RoutineBacked("film", SVC_FQN, "viaMethod"));
        return new LspSchemaSnapshot.Built.Current(
            List.of(), Map.of(), Map.of(), classifications, Map.of());
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
