package no.sikt.graphitron.lsp;

import no.sikt.graphitron.lsp.definition.DeclarationDefinitions;
import no.sikt.graphitron.lsp.facts.DeclarationFacts;
import no.sikt.graphitron.lsp.hover.DeclarationHovers;
import no.sikt.graphitron.lsp.parsing.DeclTarget;
import no.sikt.graphitron.rewrite.catalog.LspSchemaSnapshot;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The declaration-name hover overlay and its agreement with goto-definition. Both falsifiable pieces
 * are separable: the shared {@link DeclTarget} resolver names a declaration from what the store says
 * a coordinate resolves against, and the two projections of it,
 * {@link DeclarationHovers#overlay} and {@link DeclarationDefinitions#locate}, read that declaration's
 * facts out of the same rows. The only tree-sitter-bound step, the {@code @field(name:)} trigger, is
 * shared with goto and covered by the live {@code DeclarationHoversTest}.
 *
 * <p>Every case stands up a real store: real capture of the fixture module's generated jOOQ catalog
 * and of the fixture classes for the resolver arms, and a real parse of {@code .java} files on disk
 * for the arms that answer about Java. No row is inserted by hand, so a fixture cannot claim a state
 * capture never writes, and every doc comment and position in an assertion came from parsing source.
 * Nor is any resolution handed a build: the snapshot is unavailable throughout, which is the session
 * this whole surface is for, and the one arm a build would add is a {@code @routine} field's.
 *
 * <p>The drift guard is the parity claim, and it is structural in both halves. Both projections
 * switch exhaustively over the <em>same</em> resolved target, so neither can point at a different
 * declaration and a new scope arm breaks both at compile time; and both read the same row of the
 * java-source family out of one statement's rows, one for its doc comment and one for its position, so
 * neither can be answering about a state of the source the other has not seen. One asymmetry is left,
 * and the guard is what pins it: goto jumps for every declaration the parse positioned, hover overlays
 * only those it read a doc comment for. Where the source carries one, both fire; where nothing is
 * parsed, neither does.
 */
class DeclarationHoverOverlayParityTest {

    private static final String STANDALONE_CLASS = "no.sikt.example.CustomRecord";
    private static final String RECORD_CLASS = "no.sikt.example.PersonRecord";
    private static final String POJO_CLASS = "no.sikt.example.PersonPojo";
    private static final String SERVICE_CLASS = "no.sikt.example.PriceService";
    private static final String BACKING_SERVICE = "no.sikt.example.BackingService";

    private static final String FIXTURE_SERVICE = "no.sikt.graphitron.lsp.fixtures.R157Service";
    private static final String FIXTURE_RECORD = "no.sikt.graphitron.lsp.fixtures.R157FilmRecord";
    private static final String FIXTURE_POJO = "no.sikt.graphitron.lsp.fixtures.R157FilmPojo";
    private static final String FIXTURE_JOOQ_RECORD =
        "no.sikt.graphitron.rewrite.test.jooq.tables.records.FilmRecord";

    /**
     * One graph covering every arm both halves have: a type bound to a table, a type a producer grounds
     * on a record, one it grounds on a bean, one it grounds on the row type of a table, a field whose
     * own {@code @service} names a method, and one type per Java declaration the overlay cases describe.
     * Each backing is a producer return the census carries, so no assertion below rests on a shape only
     * a hand-built store could have, and every declaration an overlay reads is reachable from a
     * coordinate an author could put a cursor on.
     */
    private static final String SDL = """
        type Query {
            film: Film
            actor: Actor
            card: FilmCard @service(service: {className: "%1$s", method: "makeFilmRecord"})
            pojo: FilmPojoView @service(service: {className: "%1$s", method: "makeFilmPojo"})
            row: FilmRow @service(service: {className: "%1$s", method: "makeFilmRow"})
            priced: Priced
            custom: CustomView @service(service: {className: "%3$s", method: "makeCustom"})
            person: PersonView @service(service: {className: "%3$s", method: "makePerson"})
            bean: BeanView @service(service: {className: "%3$s", method: "makeBean"})
        }
        type Film @table(name: "film") {
            title: String
            release_year: Int
        }
        type Actor @table(name: "actor") { first_name: String }
        type FilmCard { title: String }
        type FilmPojoView { title: String }
        type FilmRow { title: String }
        type Priced { price: Int @service(service: {className: "%2$s", method: "price"}) }
        type CustomView { anything: String }
        type PersonView { firstName: String }
        type BeanView { firstName: String }
        """.formatted(FIXTURE_SERVICE, SERVICE_CLASS, BACKING_SERVICE);

    // ===== resolver: SDL coordinate -> named declaration =====

    @Test
    void typeNameResolvesPerScope(@TempDir Path root) {
        try (var store = parityStore(root)) {
            var film = new DeclTarget.CatalogTable("film", store.tableClassFqn("film"));
            assertThat(target(store, type("Film"))).isEqualTo(film);
            assertThat(target(store, type("FilmCard")))
                .isEqualTo(new DeclTarget.SourceClass(FIXTURE_RECORD));
            assertThat(target(store, type("FilmPojoView")))
                .isEqualTo(new DeclTarget.SourceClass(FIXTURE_POJO));
            // A type grounded on the row type of a table names that table, not the generated class as
            // a class: the same declaration the @table-bound type above resolves to.
            assertThat(target(store, type("FilmRow"))).isEqualTo(film);
            assertThat(target(store, type("Query"))).isInstanceOf(DeclTarget.None.class);
            assertThat(target(store, type("Unknown"))).isInstanceOf(DeclTarget.None.class);
        }
    }

    /**
     * The class-scoped arms read the store: which of a record's components or a class's accessors a
     * member name resolves to, and therefore whether the declaration behind it is a field or a
     * method, is the member-slot relation's answer.
     */
    @Test
    void fieldNameResolvesPerScope(@TempDir Path root) {
        try (var store = parityStore(root)) {
            var title = new DeclTarget.CatalogColumn("film", store.tableClassFqn("film"), "TITLE");
            // The author's spelling is the SQL name; the target carries the generated field's, which
            // is what the class declares and what either consumer then reads about it.
            assertThat(target(store, member("Film", "title"))).isEqualTo(title);
            assertThat(target(store, member("FilmRow", "title"))).isEqualTo(title);
            // Record component and bean accessor field arms.
            assertThat(target(store, member("FilmCard", "title")))
                .isEqualTo(new DeclTarget.SourceField(FIXTURE_RECORD, "title"));
            assertThat(target(store, member("FilmPojoView", "title")))
                .isEqualTo(new DeclTarget.SourceMethod(FIXTURE_POJO, "getTitle", 0));
            // A method-backed (@service) field name resolves to its bound method, with
            // the arity read off the census, taking precedence over the parent's scope.
            assertThat(target(store, member("Priced", "price")))
                .isEqualTo(new DeclTarget.SourceMethod(SERVICE_CLASS, "price", 1));
            // Unknown column / unknown member / no scope all yield no target.
            assertThat(target(store, member("Film", "no_such_column")))
                .isInstanceOf(DeclTarget.None.class);
            assertThat(target(store, member("FilmCard", "noSuchMember")))
                .isInstanceOf(DeclTarget.None.class);
            assertThat(target(store, member("Query", "whatever")))
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
            assertThat(target(store, type("FilmRow")))
                .isEqualTo(new DeclTarget.SourceClass(FIXTURE_JOOQ_RECORD));
            assertThat(target(store, member("FilmRow", "title"))).isInstanceOf(DeclTarget.None.class);
        }
    }

    // ===== overlay: named declaration -> the text the store holds for it =====

    @Test
    void everyArmReadsWhatTheStoreHoldsForItsDeclaration(@TempDir Path root) {
        try (var store = parityStore(root)) {
            String filmFqn = writeSources(store, root);

            // The table arm reads its database comment first and the generated class's doc comment
            // only where there is none, so both halves of that precedence are asserted: film carries
            // a comment and a parsed Javadoc and answers with the comment, actor carries only the
            // parse and answers with it.
            assertThat(overlay(store, type("Film"))).isEqualTo("One film in the rental catalogue.");
            assertThat(overlay(store, type("Actor")))
                .as("the class Javadoc is what a table with no comment falls back to")
                .isEqualTo("People who appear in films.");
            assertThat(overlay(store, member("Film", "title"))).isEqualTo("The film's title.");
            assertThat(overlay(store, type("CustomView"))).isEqualTo("A hand-written record.");
            assertThat(overlay(store, member("BeanView", "firstName")))
                .isEqualTo("Reads the first name.");
            // A non-zero arity keys the overload the resolution named, rather than assuming zero.
            assertThat(overlay(store, member("Priced", "price"))).isEqualTo("Prices one film.");
            assertThat(overlay(store, member("Query", "whatever"))).isEmpty();

            // The same declaration the column arm just described, reached the other way: a member
            // keyed by class and field name, which is the lookup a record component resolves through.
            var title = resolve(store, member("Film", "title"));
            assertThat(DeclarationHovers.overlay(
                new DeclTarget.SourceField(filmFqn, "TITLE"), title.rows()))
                .isEqualTo("The film's title.");
        }
    }

    /**
     * The column arm matches under either spelling the census carries, so a target resolved from the
     * jOOQ constant and one resolved from the SQL column name describe the same column. Goto has only
     * the generated field's own name to key on, which is what the resolution hands it.
     */
    @Test
    void theColumnArmAnswersUnderEitherSpelling(@TempDir Path root) {
        try (var store = parityStore(root)) {
            String filmFqn = writeSources(store, root);
            var title = resolve(store, member("Film", "title"));

            assertThat(DeclarationHovers.overlay(
                new DeclTarget.CatalogColumn("film", filmFqn, "title"), title.rows()))
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
        try (var store = parityStore(root)) {
            writeSources(store, root);
            var component = resolve(store, member("PersonView", "firstName"));

            assertThat(component.target())
                .isEqualTo(new DeclTarget.SourceField(RECORD_CLASS, "firstName"));
            assertThat(DeclarationDefinitions.locate(component.target(), component.rows()))
                .as("the component is positioned, so goto jumps")
                .isPresent();
            assertThat(DeclarationHovers.overlay(component.target(), component.rows()))
                .as("its header doc comment is not retained for the component's own declaration")
                .isEmpty();
        }
    }

    /**
     * A doc comment nothing has parsed is absence, not an empty paragraph under the classification.
     *
     * <p>The catalog arm is asserted on {@code actor} rather than {@code film}, and the choice is the
     * subject: a table the database comments on overlays that comment with nothing parsed at all, so
     * on {@code film} an empty overlay would never have been reachable and this case would have been
     * asserting the wrong absence.
     */
    @Test
    void anUnparsedDeclarationOverlaysNothing(@TempDir Path root) {
        try (var store = parityStore(root)) {
            assertThat(overlay(store, type("Actor"))).isEmpty();
            assertThat(overlay(store, type("PersonView"))).isEmpty();
            assertThat(overlay(store, member("Priced", "price"))).isEmpty();
        }
    }

    // ===== drift guard: overlay-presence <=> jump-presence, per variant =====

    /**
     * The biconditional holds for the source-derived arms, and those are the ones it is a drift guard
     * over: their overlay text has exactly one origin, the parsed declaration goto jumps to, so a
     * jump without an overlay or an overlay without a jump means the two disagree about what the
     * store holds.
     *
     * <p>The catalog arms are deliberately not in this loop, and the case below says why. Their
     * overlay has a second origin that no parse feeds.
     */
    @Test
    void overlayIsPresentExactlyWhenGotoJumpsForTheSourceDerivedArms(
        @TempDir Path parsed, @TempDir Path unparsed
    ) {
        try (var store = parityStore(parsed);
             var bare = parityStore(unparsed)) {
            writeSources(store, parsed);

            // A field declaration with a doc comment is the one variant no coordinate reaches: a column
            // constant resolves to the catalog arm below, and a record component's header comment is
            // not retained by the parse, so this pair is asserted from the column coordinate's rows.
            var column = resolve(store, member("Film", "title"));
            var generatedField = new DeclTarget.SourceField(store.tableClassFqn("film"), "TITLE");
            assertThat(DeclarationDefinitions.locate(generatedField, column.rows())).isPresent();
            assertThat(DeclarationHovers.overlay(generatedField, column.rows())).isNotEmpty();
            var unparsedColumn = resolve(bare, member("Film", "title"));
            assertThat(DeclarationDefinitions.locate(generatedField, unparsedColumn.rows())).isEmpty();
            assertThat(DeclarationHovers.overlay(generatedField, unparsedColumn.rows())).isEmpty();

            for (DeclarationFacts.Coord coord : List.of(
                type("CustomView"),
                member("BeanView", "firstName"),
                member("Priced", "price")
            )) {
                // One parsed declaration behind both: goto jumps AND hover overlays.
                var found = resolve(store, coord);
                assertThat(DeclarationDefinitions.locate(found.target(), found.rows()))
                    .as("goto jump for %s when parsed", found.target()).isPresent();
                assertThat(DeclarationHovers.overlay(found.target(), found.rows()))
                    .as("hover overlay for %s when parsed", found.target()).isNotEmpty();
                // A store whose catalog was captured but whose sources never were: neither fires.
                var missing = resolve(bare, coord);
                assertThat(DeclarationDefinitions.locate(missing.target(), missing.rows()))
                    .as("goto jump for %s when unparsed", missing.target()).isEmpty();
                assertThat(DeclarationHovers.overlay(missing.target(), missing.rows()))
                    .as("hover overlay for %s when unparsed", missing.target()).isEmpty();
            }
        }
    }

    /**
     * A catalog arm's overlay is not a function of the parse, so the biconditional above does not
     * reach it, and that asymmetry is the design rather than a gap in the guard. Goto still needs a
     * parsed generated declaration to jump to, while the overlay has the database comment to fall
     * back on, which capture wrote from the catalog and no source cadence touches. So a commented
     * table or column describes itself in a store whose sources were never walked, which is the
     * point of capturing the comment at all.
     *
     * <p>What still holds, and is asserted here, is that the parse is the <em>only</em> other origin:
     * on a catalog target the database says nothing about, overlay and jump are absent together, so
     * the fallback rather than the arm is what makes the commented case asymmetric.
     */
    @Test
    void aCatalogArmOverlaysItsDatabaseCommentWithNothingParsed(@TempDir Path unparsed) {
        try (var bare = parityStore(unparsed)) {
            for (DeclarationFacts.Coord coord : List.of(type("Film"), member("Film", "title"))) {
                var commented = resolve(bare, coord);
                assertThat(DeclarationDefinitions.locate(commented.target(), commented.rows()))
                    .as("goto jump for %s, which needs a parsed declaration", commented.target())
                    .isEmpty();
                assertThat(DeclarationHovers.overlay(commented.target(), commented.rows()))
                    .as("hover overlay for %s, which needs only the comment", commented.target())
                    .isNotEmpty();
            }

            for (DeclarationFacts.Coord coord : List.of(type("Actor"), member("Film", "release_year"))) {
                var commentless = resolve(bare, coord);
                assertThat(DeclarationDefinitions.locate(commentless.target(), commentless.rows()))
                    .as("goto jump for %s", commentless.target()).isEmpty();
                assertThat(DeclarationHovers.overlay(commentless.target(), commentless.rows()))
                    .as("hover overlay for %s, the fallback being empty too", commentless.target())
                    .isEmpty();
            }
        }
    }

    @Test
    void noBackingTargetNeverJumpsAndNeverOverlays(@TempDir Path root) {
        try (var store = parityStore(root)) {
            writeSources(store, root);
            var none = resolve(store, member("Query", "whatever"));
            assertThat(none.target()).isInstanceOf(DeclTarget.None.class);
            assertThat(DeclarationDefinitions.locate(none.target(), none.rows())).isEmpty();
            assertThat(DeclarationHovers.overlay(none.target(), none.rows())).isEmpty();
        }
    }

    // ===== fixtures =====

    /** One coordinate's resolution and the rows it resolved from, which both projections then read. */
    private record Resolved(DeclTarget target, DeclarationFacts.Rows rows) {}

    /**
     * One statement, then the resolution over what it brought back, with no build behind it. Every
     * case goes through here, so nothing below can read a substrate the shipped surfaces do not.
     */
    private static Resolved resolve(StoreFixture store, DeclarationFacts.Coord coord) {
        var handle = store.handle();
        var projected = DeclTarget.projectedMethod(coord, LspSchemaSnapshot.unavailable());
        var rows = DeclarationFacts.of(handle, coord, projected);
        return new Resolved(DeclTarget.of(coord, rows, projected), rows);
    }

    private static DeclTarget target(StoreFixture store, DeclarationFacts.Coord coord) {
        return resolve(store, coord).target();
    }

    private static String overlay(StoreFixture store, DeclarationFacts.Coord coord) {
        var found = resolve(store, coord);
        return DeclarationHovers.overlay(found.target(), found.rows());
    }

    private static DeclarationFacts.Coord type(String typeName) {
        return new DeclarationFacts.Coord.Type(typeName);
    }

    private static DeclarationFacts.Coord member(String typeName, String memberName) {
        return new DeclarationFacts.Coord.Member(typeName, memberName);
    }

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
        // A second generated table class, for the table arm's other half: the database declares no
        // comment on actor, so its class Javadoc is what the overlay falls back to. Without a
        // commentless table parsed, the fallback branch has no case.
        store.withJavaSource(root, store.tableClassFqn("actor"), """
            /** People who appear in films. */
            public class Actor {
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
     * The store every case reads: the fixture module's generated catalog for the table and column
     * arms, the fixture classes for the types a producer grounds on them, and the census entries the
     * fixtures cannot supply, which are the classes the parsed sources declare and one method taking a
     * parameter, whose count is the arity a method-backed field resolves at.
     */
    private static StoreFixture parityStore(Path root) {
        return StoreFixture.ofCatalog(root, SDL, Stream.concat(
            StoreFixture.backingClasses().stream(),
            Stream.of(
                StoreFixture.jarClass(SERVICE_CLASS, List.of(
                    StoreFixture.method("price", "Field",
                        StoreFixture.parameter("ctx", "DSLContext")))),
                StoreFixture.jarClass(BACKING_SERVICE, List.of(
                    StoreFixture.producing("makeCustom", STANDALONE_CLASS),
                    StoreFixture.producing("makePerson", RECORD_CLASS),
                    StoreFixture.producing("makeBean", POJO_CLASS))),
                StoreFixture.jarClass(STANDALONE_CLASS, List.of()),
                StoreFixture.jarRecord(RECORD_CLASS,
                    StoreFixture.component("firstName", "String")),
                StoreFixture.jarClass(POJO_CLASS, List.of(
                    StoreFixture.method("getFirstName", "String"))))).toList());
    }
}
