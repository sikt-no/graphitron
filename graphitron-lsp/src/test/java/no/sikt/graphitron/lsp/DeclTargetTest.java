package no.sikt.graphitron.lsp;

import no.sikt.graphitron.model.config.ClasspathEntry;
import no.sikt.graphitron.model.test.ClasspathCorpus;
import no.sikt.graphitron.lsp.facts.DeclarationFacts;
import no.sikt.graphitron.lsp.parsing.DeclTarget;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The {@link DeclTarget} resolver go-to-definition jumps through: which declaration an SDL coordinate
 * names, from what the store says it resolves against, per scope. Every case stands up a real store,
 * a real capture of the fixture module's generated jOOQ catalog and of the fixture classes, with no
 * row inserted by hand and no build behind it. Where each target jumps is
 * {@code DeclarationDefinitionsTest}'s.
 */
class DeclTargetTest {

    /** One corpus class both prices a film and grounds the three class-backed views. */
    private static final String SERVICE_CLASS = "no.sikt.graphitron.rewrite.test.services.NamedMemberService";
    private static final String BACKING_SERVICE = SERVICE_CLASS;

    private static final String FIXTURE_SERVICE = "no.sikt.graphitron.lsp.fixtures.R157Service";
    private static final String FIXTURE_RECORD = "no.sikt.graphitron.lsp.fixtures.R157FilmRecord";
    private static final String FIXTURE_POJO = "no.sikt.graphitron.lsp.fixtures.R157FilmPojo";
    private static final String FIXTURE_JOOQ_RECORD =
        "no.sikt.graphitron.rewrite.test.jooq.public_.tables.records.FilmRecord";
    /** The generated convenience class a {@code @routine} field's call surface lives on. */
    private static final String ROUTINES_CLASS = "no.sikt.graphitron.rewrite.test.jooq.public_.Routines";

    /**
     * One graph covering every arm both halves have: a type bound to a table, a type a producer grounds
     * on a record, one it grounds on a bean, one it grounds on the row type of a table, a field whose
     * own {@code @service} names a method, and one whose {@code @routine} names a generated call.
     * Each backing is a producer return the census carries, so no assertion below rests on a shape only
     * a hand-built store could have.
     */
    private static final String SDL = """
        type Query {
            film: Film
            actor: Actor
            card: FilmCard @service(service: {className: "%1$s", method: "makeFilmRecord"})
            pojo: FilmPojoView @service(service: {className: "%1$s", method: "makeFilmPojo"})
            row: FilmRow @service(service: {className: "%1$s", method: "makeFilmRow"})
            priced: Priced
            custom: CustomView @service(service: {className: "%3$s", method: "makeMemberless"})
            person: PersonView @service(service: {className: "%3$s", method: "makeRecord"})
            bean: BeanView @service(service: {className: "%3$s", method: "makeBean"})
            called: Called
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
        type Called { films: Film @routine(name: "films_for_actor") }
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
            // A @routine field resolves the same way, out of the catalog census rather than the class
            // one: the generated call its FROM clause makes, and that call's own parameter count.
            assertThat(target(store, member("Called", "films")))
                .isEqualTo(new DeclTarget.SourceMethod(ROUTINES_CLASS, "filmsForActor", 2));
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

        try (var store = StoreFixture.of(root, sdl, StoreFixture.testClasses())) {
            assertThat(target(store, type("FilmRow")))
                .isEqualTo(new DeclTarget.SourceClass(FIXTURE_JOOQ_RECORD));
            assertThat(target(store, member("FilmRow", "title"))).isInstanceOf(DeclTarget.None.class);
        }
    }

    // ===== fixtures =====

    /** One coordinate's resolution and the rows it resolved from. */
    private record Resolved(DeclTarget target, DeclarationFacts.Rows rows) {}

    /**
     * One statement, then the resolution over what it brought back, as go-to-definition reads it.
     */
    private static Resolved resolve(StoreFixture store, DeclarationFacts.Coord coord) {
        var rows = DeclarationFacts.of(store.handle(), coord);
        return new Resolved(DeclTarget.of(coord, rows), rows);
    }

    private static DeclTarget target(StoreFixture store, DeclarationFacts.Coord coord) {
        return resolve(store, coord).target();
    }

    private static DeclarationFacts.Coord type(String typeName) {
        return new DeclarationFacts.Coord.Type(typeName);
    }

    private static DeclarationFacts.Coord member(String typeName, String memberName) {
        return new DeclarationFacts.Coord.Member(typeName, memberName);
    }

    /**
     * The store every case reads: the fixture module's generated catalog for the table and column
     * scopes, the fixture classes for the types a producer grounds on them, and the service corpus,
     * among them one method taking a parameter, whose count is the arity a method-backed field
     * resolves at.
     */
    private static StoreFixture parityStore(Path root) {
        return StoreFixture.ofCatalog(root, SDL, parityClasspath());
    }

    private static List<ClasspathEntry> parityClasspath() {
        return Stream.concat(StoreFixture.testClasses().stream(), Stream.of(ClasspathCorpus.service()))
            .toList();
    }
}
