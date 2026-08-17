package no.sikt.graphitron.lsp;

import no.sikt.graphitron.lsp.definition.DeclarationDefinitions;
import no.sikt.graphitron.lsp.hover.DeclarationHovers;
import no.sikt.graphitron.lsp.parsing.DeclTarget;
import no.sikt.graphitron.rewrite.catalog.FieldClassification;
import no.sikt.graphitron.rewrite.catalog.LspSchemaSnapshot;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The declaration-name hover overlay and its agreement with goto-definition. Both falsifiable pieces
 * are separable: the shared {@link DeclTarget} resolver names a declaration from what the store says
 * a coordinate resolves against, and the two projections of it,
 * {@link DeclarationHovers#overlay} and {@link DeclarationDefinitions#locate}, are queries against one
 * store. The only tree-sitter-bound step, the {@code @field(name:)} trigger, is shared with goto and
 * covered by the live {@code DeclarationHoversTest}.
 *
 * <p>Every case stands up a real store: real capture of the fixture module's generated jOOQ catalog
 * and of the fixture classes for the resolver arms, and a real parse of {@code .java} files on disk
 * for the arms that answer about Java. No row is inserted by hand, so a fixture cannot claim a state
 * capture never writes, and every doc comment and position in an assertion came from parsing source.
 * One projection value is still hand-built, and it carries exactly the one question the resolver still
 * puts to the projection: which Java method a method-backed field binds to.
 *
 * <p>The drift guard is the parity claim, and it is structural in both halves again. Both projections
 * switch exhaustively over the <em>same</em> resolved target, so neither can point at a different
 * declaration and a new scope arm breaks both at compile time; and both read the same row of the
 * java-source family, one for its doc comment and one for its position, so neither can be answering
 * about a state of the source the other has not seen. One asymmetry is left, and the guard is what
 * pins it: goto jumps for every declaration the parse positioned, hover overlays only those it read a
 * doc comment for. Where the source carries one, both fire; where nothing is parsed, neither does.
 */
class DeclarationHoverOverlayParityTest {

    private static final String STANDALONE_CLASS = "no.sikt.example.CustomRecord";
    private static final String RECORD_CLASS = "no.sikt.example.PersonRecord";
    private static final String POJO_CLASS = "no.sikt.example.PersonPojo";
    private static final String SERVICE_CLASS = "no.sikt.example.PriceService";

    /** The graph is beside the point in the overlay cases; the subject is the catalog and the source. */
    private static final String PLACEHOLDER_SDL = "type Query { placeholder: Int }\n";

    private static final String FIXTURE_SERVICE = "no.sikt.graphitron.lsp.fixtures.R157Service";
    private static final String FIXTURE_RECORD = "no.sikt.graphitron.lsp.fixtures.R157FilmRecord";
    private static final String FIXTURE_POJO = "no.sikt.graphitron.lsp.fixtures.R157FilmPojo";
    private static final String FIXTURE_JOOQ_RECORD =
        "no.sikt.graphitron.rewrite.test.jooq.tables.records.FilmRecord";

    /**
     * One graph covering every arm the resolver has: a type bound to a table, a type a producer
     * grounds on a record, one it grounds on a bean, one it grounds on the row type of a table, and a
     * field whose own {@code @service} names a method. Each backing is a real producer return read off
     * the fixture classes, so no assertion below rests on a shape only a hand-built store could have.
     */
    private static final String RESOLVER_SDL = """
        type Query {
            film: Film
            card: FilmCard @service(service: {className: "%1$s", method: "makeFilmRecord"})
            pojo: FilmPojoView @service(service: {className: "%1$s", method: "makeFilmPojo"})
            row: FilmRow @service(service: {className: "%1$s", method: "makeFilmRow"})
            priced: Priced
        }
        type Film @table(name: "film") { title: String }
        type FilmCard { title: String }
        type FilmPojoView { title: String }
        type FilmRow { title: String }
        type Priced { price: Int @service(service: {className: "%2$s", method: "price"}) }
        """.formatted(FIXTURE_SERVICE, SERVICE_CLASS);

    // ===== resolver: SDL coordinate -> named declaration =====

    @Test
    void typeNameResolvesPerScope(@TempDir Path root) {
        try (var store = resolverStore(root)) {
            var handle = store.handle();
            var film = new DeclTarget.CatalogTable("film", store.tableClassFqn("film"));
            assertThat(DeclTarget.ofType("Film", handle)).isEqualTo(film);
            assertThat(DeclTarget.ofType("FilmCard", handle))
                .isEqualTo(new DeclTarget.SourceClass(FIXTURE_RECORD));
            assertThat(DeclTarget.ofType("FilmPojoView", handle))
                .isEqualTo(new DeclTarget.SourceClass(FIXTURE_POJO));
            // A type grounded on the row type of a table names that table, not the generated class as
            // a class: the same declaration the @table-bound type above resolves to.
            assertThat(DeclTarget.ofType("FilmRow", handle)).isEqualTo(film);
            assertThat(DeclTarget.ofType("Query", handle)).isInstanceOf(DeclTarget.None.class);
            assertThat(DeclTarget.ofType("Unknown", handle)).isInstanceOf(DeclTarget.None.class);
        }
    }

    /**
     * The class-scoped arms read the store: which of a record's components or a class's accessors a
     * member name resolves to, and therefore whether the declaration behind it is a field or a
     * method, is the member-slot relation's answer.
     */
    @Test
    void fieldNameResolvesPerScope(@TempDir Path root) {
        try (var store = resolverStore(root)) {
            var handle = store.handle();
            var built = built();
            var title = new DeclTarget.CatalogColumn("film", store.tableClassFqn("film"), "TITLE");
            // The author's spelling is the SQL name; the target carries the generated field's, which
            // is what the class declares and what either consumer then reads about it.
            assertThat(DeclTarget.ofField("Film", "title", built, handle)).isEqualTo(title);
            assertThat(DeclTarget.ofField("FilmRow", "title", built, handle)).isEqualTo(title);
            // Record component and bean accessor field arms.
            assertThat(DeclTarget.ofField("FilmCard", "title", built, handle))
                .isEqualTo(new DeclTarget.SourceField(FIXTURE_RECORD, "title"));
            assertThat(DeclTarget.ofField("FilmPojoView", "title", built, handle))
                .isEqualTo(new DeclTarget.SourceMethod(FIXTURE_POJO, "getTitle", 0));
            // A method-backed (@service) field name resolves to its bound method, with
            // the arity read off the census, taking precedence over the parent's scope.
            assertThat(DeclTarget.ofField("Priced", "price", built, handle))
                .isEqualTo(new DeclTarget.SourceMethod(SERVICE_CLASS, "price", 1));
            // Unknown column / unknown member / no scope all yield no target.
            assertThat(DeclTarget.ofField("Film", "no_such_column", built, handle))
                .isInstanceOf(DeclTarget.None.class);
            assertThat(DeclTarget.ofField("FilmCard", "noSuchMember", built, handle))
                .isInstanceOf(DeclTarget.None.class);
            assertThat(DeclTarget.ofField("Query", "whatever", built, handle))
                .isInstanceOf(DeclTarget.None.class);
        }
    }

    /**
     * A workspace whose jOOQ model has been generated but whose catalog this graph never captured: the
     * row type is then a class no table claims, so the type is scoped to it as a class, and the class
     * census holds no slots for it because it excludes the generated package by design. The type name
     * still names the class, and a member name written inside it names nothing.
     *
     * <p>This is where the incumbent projection answered with the backing class for <em>any</em> member
     * name, a jOOQ record being the one shape it held no member keys for. That degrade was standing in
     * for facts it did not have rather than naming a declaration the field binds to, and the store has
     * the same silence for a reason it can state.
     */
    @Test
    void aFieldOnAClassTheCensusHoldsNoSlotsForResolvesToNothing(@TempDir Path root) {
        String sdl = """
            type Query {
                row: FilmRow @service(service: {className: "%s", method: "makeFilmRow"})
            }
            type FilmRow { title: String }
            """.formatted(FIXTURE_SERVICE);

        try (var store = StoreFixture.of(root, sdl, StoreFixture.backingClasses())) {
            var handle = store.handle();
            assertThat(DeclTarget.ofType("FilmRow", handle))
                .isEqualTo(new DeclTarget.SourceClass(FIXTURE_JOOQ_RECORD));
            assertThat(DeclTarget.ofField("FilmRow", "title", built(), handle))
                .isInstanceOf(DeclTarget.None.class);
        }
    }

    // ===== overlay: named declaration -> the text the store holds for it =====

    @Test
    void everyArmReadsWhatTheStoreHoldsForItsDeclaration(@TempDir Path root) {
        try (var store = StoreFixture.ofCatalog(root, PLACEHOLDER_SDL)) {
            String filmFqn = writeSources(store, root);
            var handle = store.handle();

            // The generated table class's doc comment: the fixture database carries no comment on
            // film, so the class Javadoc is what a table with nothing written about it falls back to.
            assertThat(DeclarationHovers.overlay(new DeclTarget.CatalogTable("film", filmFqn), handle))
                .isEqualTo("The film table.");
            assertThat(DeclarationHovers.overlay(
                new DeclTarget.CatalogColumn("film", filmFqn, "TITLE"), handle))
                .isEqualTo("The film's title.");
            assertThat(DeclarationHovers.overlay(new DeclTarget.SourceClass(STANDALONE_CLASS), handle))
                .isEqualTo("A hand-written record.");
            // The same declaration the column arm just described, reached the other way: a member
            // keyed by class and field name, which is the lookup a record component resolves through.
            assertThat(DeclarationHovers.overlay(new DeclTarget.SourceField(filmFqn, "TITLE"), handle))
                .isEqualTo("The film's title.");
            assertThat(DeclarationHovers.overlay(new DeclTarget.SourceMethod(POJO_CLASS, "getFirstName", 0), handle))
                .isEqualTo("Reads the first name.");
            // A non-zero arity keys the overload the resolution named, rather than assuming zero.
            assertThat(DeclarationHovers.overlay(new DeclTarget.SourceMethod(SERVICE_CLASS, "price", 1), handle))
                .isEqualTo("Prices one film.");
            assertThat(DeclarationHovers.overlay(new DeclTarget.None(), handle)).isEmpty();
        }
    }

    /**
     * The column arm matches under either spelling the census carries, so a target resolved from the
     * jOOQ constant and one resolved from the SQL column name describe the same column. Goto has only
     * the generated field's own name to key on, which is what the resolution hands it.
     */
    @Test
    void theColumnArmAnswersUnderEitherSpelling(@TempDir Path root) {
        try (var store = StoreFixture.ofCatalog(root, PLACEHOLDER_SDL)) {
            String filmFqn = writeSources(store, root);
            var handle = store.handle();

            assertThat(DeclarationHovers.overlay(
                new DeclTarget.CatalogColumn("film", filmFqn, "title"), handle))
                .isEqualTo("The film's title.");
        }
    }

    /**
     * A record component is where the two surfaces genuinely part, and not because they read different
     * substrates: they read one row, and the parse positioned that declaration but did not retain for
     * it the doc comment written in the record's header, so goto jumps and there is nothing to overlay.
     * Pinned rather than fixed here, since it is a property of the parse; the incumbent overlay had the
     * same gap, masked by a hand-built index that asserted a component Javadoc no parse produces.
     */
    @Test
    void aRecordComponentJumpsButHasNoDocCommentToOverlay(@TempDir Path root) {
        try (var store = StoreFixture.ofCatalog(root, PLACEHOLDER_SDL)) {
            writeSources(store, root);
            var target = new DeclTarget.SourceField(RECORD_CLASS, "firstName");

            assertThat(DeclarationDefinitions.locate(target, store.handle()))
                .as("the component is positioned, so goto jumps")
                .isPresent();
            assertThat(DeclarationHovers.overlay(target, store.handle()))
                .as("its header doc comment is not retained for the component's own declaration")
                .isEmpty();
        }
    }

    /** A doc comment nothing has parsed is absence, not an empty paragraph under the classification. */
    @Test
    void anUnparsedDeclarationOverlaysNothing(@TempDir Path root) {
        try (var store = StoreFixture.ofCatalog(root, PLACEHOLDER_SDL)) {
            var handle = store.handle();
            String filmFqn = store.tableClassFqn("film");

            assertThat(DeclarationHovers.overlay(new DeclTarget.CatalogTable("film", filmFqn), handle)).isEmpty();
            assertThat(DeclarationHovers.overlay(new DeclTarget.SourceClass(RECORD_CLASS), handle)).isEmpty();
            assertThat(DeclarationHovers.overlay(
                new DeclTarget.SourceMethod(SERVICE_CLASS, "price", 1), handle)).isEmpty();
        }
    }

    // ===== drift guard: overlay-presence <=> jump-presence, per variant =====

    @Test
    void overlayIsPresentExactlyWhenGotoJumps(@TempDir Path parsed, @TempDir Path unparsed) {
        try (var store = StoreFixture.ofCatalog(parsed, PLACEHOLDER_SDL);
             var bare = StoreFixture.ofCatalog(unparsed, PLACEHOLDER_SDL)) {
            String filmFqn = writeSources(store, parsed);

            for (DeclTarget target : List.of(
                new DeclTarget.CatalogTable("film", filmFqn),
                new DeclTarget.CatalogColumn("film", filmFqn, "TITLE"),
                new DeclTarget.SourceClass(STANDALONE_CLASS),
                new DeclTarget.SourceField(filmFqn, "TITLE"),
                new DeclTarget.SourceMethod(POJO_CLASS, "getFirstName", 0),
                new DeclTarget.SourceMethod(SERVICE_CLASS, "price", 1)
            )) {
                // One parsed declaration behind both: goto jumps AND hover overlays.
                assertThat(DeclarationDefinitions.locate(target, store.handle()))
                    .as("goto jump for %s when parsed", target).isPresent();
                assertThat(DeclarationHovers.overlay(target, store.handle()))
                    .as("hover overlay for %s when parsed", target).isNotEmpty();
                // A store whose catalog was captured but whose sources never were: neither fires.
                assertThat(DeclarationDefinitions.locate(target, bare.handle()))
                    .as("goto jump for %s when unparsed", target).isEmpty();
                assertThat(DeclarationHovers.overlay(target, bare.handle()))
                    .as("hover overlay for %s when unparsed", target).isEmpty();
            }
        }
    }

    @Test
    void noBackingTargetNeverJumpsAndNeverOverlays(@TempDir Path root) {
        try (var store = StoreFixture.ofCatalog(root, PLACEHOLDER_SDL)) {
            writeSources(store, root);
            var none = new DeclTarget.None();
            assertThat(DeclarationDefinitions.locate(none, store.handle())).isEmpty();
            assertThat(DeclarationHovers.overlay(none, store.handle())).isEmpty();
        }
    }

    // ===== fixtures =====

    /**
     * The Java sources every overlay arm reads, parsed into the store's java-source family. The
     * generated table class is written under the FQN the catalog walk actually captured, so the join
     * between the two populations is a real one rather than a spelled-out naming strategy.
     *
     * @return that FQN, which the catalog arms are keyed on
     */
    private static String writeSources(StoreFixture store, Path root) {
        String filmFqn = store.tableClassFqn("film");
        store.withJavaSource(root, filmFqn, """
            /** The film table. */
            public class Film {
                /** The film's title. */
                public final Object TITLE = null;
            }
            """);
        store.withJavaSource(root, STANDALONE_CLASS, """
            /** A hand-written record. */
            public class CustomRecord {
            }
            """);
        store.withJavaSource(root, RECORD_CLASS, """
            /** A person. */
            public record PersonRecord(
                /** The person's first name. */
                String firstName
            ) {
            }
            """);
        store.withJavaSource(root, POJO_CLASS, """
            /** A person, as a bean. */
            public class PersonPojo {
                /** Reads the first name. */
                public String getFirstName() { return null; }
            }
            """);
        store.withJavaSource(root, SERVICE_CLASS, """
            /** Prices films. */
            public class PriceService {
                /** Prices one film. */
                public Object price(Object table) { return null; }
            }
            """);
        return filmFqn;
    }

    /**
     * The store the resolver cases read: the fixture module's generated catalog for the table and
     * column arms, the fixture classes for the ones a producer grounds, and one census entry the
     * fixtures cannot supply, a method taking a parameter, whose count is the arity a method-backed
     * field resolves at.
     */
    private static StoreFixture resolverStore(Path root) {
        return StoreFixture.ofCatalog(root, RESOLVER_SDL, Stream.concat(
            StoreFixture.backingClasses().stream(),
            Stream.of(StoreFixture.jarClass(SERVICE_CLASS, List.of(
                StoreFixture.method("price", "Field",
                    StoreFixture.parameter("ctx", "DSLContext")))))).toList());
    }

    /**
     * The one thing the resolver still asks the projection, which is not a backing: the classification
     * of {@code Priced.price} names the Java method that field binds to, and the resolution prefers it
     * over the parent type's own scope. Every other map is empty, and the cases above pass whatever
     * they get from it.
     */
    private static LspSchemaSnapshot.Built built() {
        Map<String, FieldClassification> classifications = Map.of(
            "Priced.price", new FieldClassification.ServiceBacked(SERVICE_CLASS, "price", false, null, null));
        return new LspSchemaSnapshot.Built.Current(List.of(), Map.of(), Map.of(), classifications, Map.of());
    }
}
