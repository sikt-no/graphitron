package no.sikt.graphitron.lsp;

import no.sikt.graphitron.lsp.parsing.LspVocabulary;
import no.sikt.graphitron.lsp.state.StoreAccess;
import no.sikt.graphitron.model.config.ClasspathEntry;
import no.sikt.graphitron.model.boot.ReadBudget;
import no.sikt.graphitron.model.boot.StoreReader;
import no.sikt.graphitron.model.read.StoreHandle;
import no.sikt.graphitron.model.test.RunawayRelation;
import no.sikt.graphitron.model.diagnostics.BuildWarning;
import no.sikt.graphitron.model.test.CapturedStore;
import no.sikt.graphitron.model.test.FactWriters;
import no.sikt.graphitron.model.jooq.JooqCatalog;
import no.sikt.graphitron.model.diagnostics.ValidationError;
import no.sikt.graphitron.model.schema.input.SchemaSource;
import org.jooq.DSLContext;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static no.sikt.graphitron.model.Tables.SQL_SCHEMA;
import static no.sikt.graphitron.model.Tables.SQL_TABLE;

/**
 * A booted fact store with one or more graphs captured into it, for the tests that read the store.
 *
 * <p>Stood up by real capture over an SDL fixture rather than by inserting rows, so a fixture cannot
 * encode a state capture never writes. The classpath goes in the same way: a test names the entries,
 * this module's test classes or the service corpus, and capture reads their classfiles as a run
 * would.
 *
 * <p>A local layer over {@link CapturedStore}, the reactor's capture level: every arm here is one of
 * that level's captures with this module's vocabulary in front of it, a generated jOOQ package
 * instead of a {@link JooqCatalog} and this module's test classes as a classpath entry. The writers
 * go through {@link FactWriters}. What stays local is what is local: the two generated packages, the
 * placeholder SDL, the backing-class fixtures, and the reads a provider makes.
 *
 * <p>Captures only. The arm that ran a real generator pass moved above this module with the one test
 * that read it, because a build is the generator's and this module does not depend on it.
 *
 * <p>The arms here borrow the test thread's store, so a case pays for a truncate rather than a boot;
 * a fixture held longer than one case opens through {@link #held()} and owns its store. The choice
 * is made where the fixture is opened, and {@code Lifetime} carries why.
 *
 * <p>{@link #handle} is over the store's own connection rather than a reader's: what a provider
 * needs is a scoped query surface, and the reader's transaction and graph resolution are
 * {@code StoreAccess}'s own business, tested through {@link #reader()}.
 */
final class StoreFixture implements AutoCloseable {

    /**
     * The graph every fixture captures under, unless a test needs to name a second one, which is the
     * capture level's own default rather than a second spelling of it.
     */
    static final String GRAPH = CapturedStore.GRAPH;

    /** The generated jOOQ model the {@code sql_} arms are captured from. */
    private static final String JOOQ_PACKAGE = "no.sikt.graphitron.rewrite.test.jooq";

    /**
     * A generated model whose two schemas landed in packages of their own, which is where a catalog
     * coordinate stops being unique: one constraint name declared in both schemas, and one declared
     * twice inside a single schema on two tables.
     */
    private static final String MULTI_SCHEMA_JOOQ_PACKAGE = "no.sikt.graphitron.rewrite.multischemafixture";

    /** The package holding the record and POJO the class-backed member arms resolve against. */
    private static final String FIXTURE_PACKAGE = "no.sikt.graphitron.lsp.fixtures.";

    /** SDL for a fixture whose whole subject is the classpath, so its schema is beside the point. */
    private static final String PLACEHOLDER_SDL = "type Query { placeholder: Int }\n";

    private final CapturedStore captured;
    private final String graphName;
    private final Path file;
    private final Path directory;
    private final Lifetime lifetime;

    private StoreFixture(CapturedStore captured, Path directory, Lifetime lifetime) {
        this.captured = captured;
        this.lifetime = lifetime;
        this.graphName = captured.graphName();
        this.file = captured.file();
        this.directory = directory;
    }

    private DSLContext dsl() {
        return captured.dsl();
    }

    /**
     * How long a fixture lives, which decides whether it may borrow the thread's store.
     *
     * <p>A borrow is what saves the boot: {@link CapturedStore}'s ordinary arms capture into the
     * store {@code ThreadConfinedStore} keeps per test thread and clear between borrows, so a case
     * pays for a truncate instead of the fact schema. The cost is that the next borrow on the
     * thread empties it. {@link CapturedStore} catches that on a later {@code dsl()} or
     * {@code reader()}, but not on a reader, a {@link StoreAccess} or a {@link StoreHandle} this
     * fixture already minted, so a fixture that lives across cases would read an emptied or
     * refilled store without an error. The rule is therefore by lifetime rather than by audit of
     * what a case calls back through: a fixture opened and closed inside one case, the
     * try-with-resources shape, borrows; anything held longer owns its store, through
     * {@link #held()}. The choice is the call site's, because only the call site knows which one it
     * is.
     */
    private enum Lifetime {
        /** Opened and closed inside one case, on the case's own thread. */
        CASE,
        /** Held across cases: a {@code @BeforeAll} or static fixture, or one that issues DDL. */
        HELD;

        CapturedStore of(Path directory, String graphName, String sdl,
                         List<ClasspathEntry> classpath) {
            return this == CASE
                ? CapturedStore.ofWith(directory, graphName, sdl, classpath)
                : CapturedStore.ownStoreWith(directory, graphName, sdl, classpath);
        }

        CapturedStore ofCatalog(Path directory, String graphName, String sdl, JooqCatalog jooq,
                                List<ClasspathEntry> classpath) {
            return this == CASE
                ? CapturedStore.ofCatalogWith(directory, graphName, sdl, jooq, classpath)
                : CapturedStore.ownStoreOfCatalogWith(directory, graphName, sdl, jooq, classpath);
        }

        CapturedStore ofFiles(Path directory, String firstName, String firstSdl,
                              String secondName, String secondSdl) {
            return this == CASE
                ? CapturedStore.ofFiles(directory, firstName, firstSdl, secondName, secondSdl)
                : CapturedStore.ownStoreOfFiles(directory, firstName, firstSdl, secondName, secondSdl);
        }
    }

    /**
     * The same arms for a fixture that outlives the case that opened it, each on a store of its
     * own: a {@code @BeforeAll} or static fixture, {@link BundledVocabulary}, and a case that
     * issues DDL through {@link #makeRunaway}. Such a fixture pays for a boot, which is the honest
     * price of a store nobody else may clear.
     */
    static Held held() {
        return Held.INSTANCE;
    }

    /** The owning arms behind {@link #held()}; see {@link Lifetime}. */
    static final class Held {

        private static final Held INSTANCE = new Held();

        private Held() {}

        StoreFixture of(Path directory, String sdl) {
            return open(Lifetime.HELD, directory, GRAPH, sdl, List.of());
        }

        StoreFixture of(Path directory, String sdl, List<ClasspathEntry> classpath) {
            return open(Lifetime.HELD, directory, GRAPH, sdl, classpath);
        }

        StoreFixture of(Path directory, String graphName, String sdl,
                        List<ClasspathEntry> classpath) {
            return open(Lifetime.HELD, directory, graphName, sdl, classpath);
        }

        StoreFixture ofClasspath(Path directory, List<ClasspathEntry> classpath) {
            return open(Lifetime.HELD, directory, GRAPH, PLACEHOLDER_SDL, classpath);
        }

        StoreFixture ofCatalog(Path directory, String sdl) {
            return ofJooqPackage(Lifetime.HELD, directory, GRAPH, sdl, List.of(), JOOQ_PACKAGE);
        }

        StoreFixture ofCatalog(Path directory, String sdl,
                               List<ClasspathEntry> classpath) {
            return ofJooqPackage(Lifetime.HELD, directory, GRAPH, sdl, classpath, JOOQ_PACKAGE);
        }

        StoreFixture ofCatalog(Path directory, String graphName, String sdl) {
            return ofJooqPackage(Lifetime.HELD, directory, graphName, sdl, List.of(), JOOQ_PACKAGE);
        }

        StoreFixture ofMultiSchemaCatalog(Path directory, String sdl) {
            return ofJooqPackage(Lifetime.HELD, directory, GRAPH, sdl, List.of(),
                MULTI_SCHEMA_JOOQ_PACKAGE);
        }

        StoreFixture ofFiles(Path directory, String firstName, String firstSdl,
                             String secondName, String secondSdl) {
            return new StoreFixture(Lifetime.HELD.ofFiles(directory, firstName, firstSdl, secondName,
                secondSdl), directory, Lifetime.HELD);
        }
    }

    /** Captures {@code sdl} alone: the shape for arms answered by SDL-derived facts. */
    static StoreFixture of(Path directory, String sdl) {
        return of(directory, GRAPH, sdl, List.of());
    }

    /** Captures {@code sdl} plus a classpath: the shape for the {@code code_} arms. */
    static StoreFixture of(Path directory, String sdl, List<ClasspathEntry> classpath) {
        return of(directory, GRAPH, sdl, classpath);
    }

    /** An SDL fixture with nothing in it, for the arms whose whole subject is the classpath. */
    static StoreFixture ofClasspath(Path directory, List<ClasspathEntry> classpath) {
        return of(directory, GRAPH, PLACEHOLDER_SDL, classpath);
    }

    /**
     * Captures {@code sdl} plus the fixture module's generated jOOQ catalog: the shape for the
     * {@code sql_} arms. The catalog is the real generated model rather than a stand-in, which is
     * what makes a table's class FQN, a column's jOOQ field name and its binding type the values a
     * consumer's editor would actually be completing against.
     */
    static StoreFixture ofCatalog(Path directory, String sdl) {
        return ofCatalog(directory, sdl, List.of());
    }

    /** The catalog shape plus a classpath, for a test whose arms span both. */
    static StoreFixture ofCatalog(Path directory, String sdl,
                                  List<ClasspathEntry> classpath) {
        return ofJooqPackage(Lifetime.CASE, directory, GRAPH, sdl, classpath, JOOQ_PACKAGE);
    }

    /**
     * The catalog shape under a graph the caller names, for a class holding several fixtures in one
     * temp directory: the graph name is what keeps their stores apart.
     */
    static StoreFixture ofCatalog(Path directory, String graphName, String sdl) {
        return ofJooqPackage(Lifetime.CASE, directory, graphName, sdl, List.of(), JOOQ_PACKAGE);
    }

    /**
     * The catalog shape over the multi-schema generated model, for the reads whose answer depends on
     * a name being ambiguous across schemas rather than on any one table's contents.
     */
    static StoreFixture ofMultiSchemaCatalog(Path directory, String sdl) {
        return ofJooqPackage(Lifetime.CASE, directory, GRAPH, sdl, List.of(), MULTI_SCHEMA_JOOQ_PACKAGE);
    }

    /** The catalog axis in this module's terms: the name of a generated package. */
    private static StoreFixture ofJooqPackage(Lifetime lifetime, Path directory, String graphName,
                                              String sdl,
                                              List<ClasspathEntry> classpath,
                                              String jooqPackage) {
        return new StoreFixture(lifetime.ofCatalog(directory, graphName, sdl,
            new JooqCatalog(jooqPackage), classpath), directory, lifetime);
    }

    /**
     * Captures two schema files into one graph. The shape for the cases where the answer has to come
     * from a file other than the one the request is about: {@link #sourceName()} is the first file, so
     * a test opens that document and asserts on what the second one declared.
     */
    static StoreFixture ofFiles(Path directory, String firstName, String firstSdl,
                                String secondName, String secondSdl) {
        return new StoreFixture(
            Lifetime.CASE.ofFiles(directory, firstName, firstSdl, secondName, secondSdl), directory,
            Lifetime.CASE);
    }

    static StoreFixture of(Path directory, String graphName, String sdl,
                           List<ClasspathEntry> classpath) {
        return open(Lifetime.CASE, directory, graphName, sdl, classpath);
    }

    private static StoreFixture open(Lifetime lifetime, Path directory, String graphName, String sdl,
                                     List<ClasspathEntry> classpath) {
        return new StoreFixture(lifetime.of(directory, graphName, sdl, classpath), directory, lifetime);
    }

    /**
     * This module's test classes as the classpath a run would read: its own build output, holding the
     * backing-class fixtures in {@code no.sikt.graphitron.lsp.fixtures}. The arms that resolve a
     * member name on a class-backed type read the store's own rule over what the reading found, and
     * that rule reads a class's declared form, so only a real classfile can say it.
     */
    static List<ClasspathEntry> testClasses() {
        return List.of(new ClasspathEntry(testClassesRoot(), ClasspathEntry.Origin.PROJECT, null, null));
    }

    private static Path testClassesRoot() {
        try {
            return Path.of(StoreFixture.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        } catch (URISyntaxException e) {
            throw new IllegalStateException("test classes root is not a file path", e);
        }
    }

    /**
     * Captures {@code sdl} through the read that keeps its stage refusals as data, so a source the
     * parser or the assembler refused lands in the store's own verdict relations. The shape for the
     * cases about what a build said, where the document not reading clean is the subject.
     */
    static StoreFixture ofRefusedSchema(Path directory, String sdl) {
        return new StoreFixture(CapturedStore.ofRefusedSchema(directory, sdl), directory,
            Lifetime.CASE);
    }

    /**
     * Captures a second graph, over a schema file of its own, into this same store. The directory is
     * the one this fixture already captured into: the capture level keys a graph's file on its name
     * inside that directory, so naming another one here is asking for something it will not do, and
     * this says so rather than writing somewhere the store does not look.
     */
    StoreFixture andGraph(Path directory, String otherGraph, String sdl,
                          List<ClasspathEntry> classpath) {
        requireOwnDirectory(directory);
        captured.andGraphWith(otherGraph, sdl, classpath);
        return this;
    }

    /**
     * Captures a second graph over the <em>same</em> schema file this fixture already captured, which
     * is the shared-file case: one document, two memberships, both true.
     */
    StoreFixture andGraphSharingTheFile(Path directory, String otherGraph) {
        requireOwnDirectory(directory);
        captured.andGraphSharingTheFile(otherGraph);
        return this;
    }

    private void requireOwnDirectory(Path other) {
        if (!directory.equals(other)) {
            throw new IllegalArgumentException("this fixture captured into " + directory
                + ", and a second graph lands there too, not in " + other);
        }
    }

    /**
     * The FQN of the generated table class for {@code tableName}, read back out of the census rather
     * than spelled out here, so a test joining the java-source family to it cannot hard-code a
     * naming strategy the generator might not be using.
     */
    String tableClassFqn(String tableName) {
        return dsl().select(SQL_TABLE.CLASS_FQN)
            .from(SQL_TABLE)
            .where(SQL_TABLE.TABLE_NAME.equalIgnoreCase(tableName))
            .fetchOptional(SQL_TABLE.CLASS_FQN)
            .orElseThrow(() -> new AssertionError("no captured table named " + tableName));
    }

    /**
     * The FQN of the generated {@code Keys} class the census recorded for {@code tableName}'s schema,
     * read back out for the same reason {@link #tableClassFqn} is: a test joining the java-source
     * family to it must not hard-code a package layout the generator might not be using.
     */
    String keysClassFqn(String tableName) {
        return dsl().select(SQL_SCHEMA.KEYS_CLASS_FQN)
            .from(SQL_SCHEMA)
            .join(SQL_TABLE).on(SQL_TABLE.SOURCE_NAME.eq(SQL_SCHEMA.SOURCE_NAME)
                .and(SQL_TABLE.TABLE_SCHEMA.eq(SQL_SCHEMA.TABLE_SCHEMA)))
            .where(SQL_TABLE.TABLE_NAME.equalIgnoreCase(tableName))
            .fetchOptional(SQL_SCHEMA.KEYS_CLASS_FQN)
            .orElseThrow(() -> new AssertionError("no captured Keys class for " + tableName));
    }

    /**
     * Parses one Java source declaring {@code classFqn} into this store's {@code java_} family, the
     * way a dev session's source watcher would. The declaration is a stand-in for the generated
     * table class the catalog walk recorded the FQN of: the join between the two populations is by
     * name across two cadences, so a source that agrees on the name is all it takes to exercise it,
     * and the schema states outright that the two may otherwise disagree.
     */
    void withJavaSource(Path sourceRoot, String classFqn, String body) {
        int lastDot = classFqn.lastIndexOf('.');
        Path directory = sourceRoot.resolve(classFqn.substring(0, lastDot).replace('.', '/'));
        try {
            Files.createDirectories(directory);
            Files.writeString(directory.resolve(classFqn.substring(lastDot + 1) + ".java"),
                "package " + classFqn.substring(0, lastDot) + ";\n" + body);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        refreshJavaSources(sourceRoot);
    }

    /**
     * Re-reads the {@code .java} files under {@code sourceRoot} into this store's {@code java_}
     * family, the way a dev session's source watcher does after an edit. Separate from
     * {@link #withJavaSource} because the file on disk is the caller's there: a test that compares
     * this family against another reader of the same file has to own where that file is.
     */
    void refreshJavaSources(Path sourceRoot) {
        var roots = List.of(sourceRoot);
        FactWriters.refreshJavaSources(dsl(), roots);
    }

    /**
     * Writes {@code warnings} into this store's warning families under {@code graph}, the way a dev
     * session's build does once the linter has run. The real writer, so a fixture cannot record a row
     * shape the build never produces; the findings are the caller's, since what a rule concludes about
     * a schema is the build's subject and not this fixture's.
     */
    void withBuildWarnings(String graph, List<BuildWarning> warnings) {
        FactWriters.buildWarningFacts(dsl(), graph, directory).write(warnings);
    }

    /** The same, under the graph this fixture captured. */
    void withBuildWarnings(List<BuildWarning> warnings) {
        withBuildWarnings(graphName, warnings);
    }

    /**
     * Writes {@code errors} into this store's rejection residue under the graph this fixture
     * captured, the way a dev session's build does once the classification walk has run. The real
     * writer, for {@link #withBuildWarnings}'s reason; what the walk concluded is the build's
     * subject and so is the caller's here.
     */
    void withValidationErrors(List<ValidationError> errors) {
        FactWriters.rejectionFacts(dsl(), graphName, directory).write(errors);
    }

    /**
     * The directive vocabulary this fixture's graph declares, which for every fixture includes
     * graphitron's own bundled definitions: capture parses them alongside whatever schema it is
     * given. A test whose subject is not the vocabulary reads {@link BundledVocabulary} instead.
     */
    LspVocabulary vocabulary() {
        return LspVocabulary.load(handle());
    }

    /**
     * A reader of this store, for the cases whose subject is the read boundary rather than a query.
     * Unbounded, as every fixture reader is: a harness naming a number would put a wall-clock
     * threshold in a tier that must not fail for being slow.
     */
    StoreReader reader() {
        return captured.reader();
    }

    /** A reader of this store under a stated budget, for the cases whose subject is the budget. */
    StoreReader reader(ReadBudget budget) {
        return captured.reader(budget);
    }

    /**
     * This store's read access as a session holds it: three unbounded readers behind one
     * {@link StoreAccess}, which is the production shape with the budgets taken out. A case whose
     * subject <em>is</em> the budgets uses {@link #access(ReadBudget, ReadBudget)} and states them.
     */
    StoreAccess access() {
        return access(graphName);
    }

    /** The same, seen as another graph, for the cases about one graph reading another's rows. */
    StoreAccess access(String otherGraph) {
        return new StoreAccess(reader(), reader(), reader(), otherGraph);
    }

    /**
     * The production shape with the budgets in, the annotation reader sharing the cursor's as the dev
     * goal has it.
     */
    StoreAccess access(ReadBudget interactive, ReadBudget sessionWide) {
        return access(interactive, interactive, sessionWide);
    }

    /**
     * The same with the annotation reader's budget stated separately, for the cases whose subject is
     * which door reached which reader. Production gives that reader the interactive budget, so a case
     * that needs to tell the two connections apart has to label them, and a budget read back as a
     * session setting is the label: it says which connection answered without timing anything.
     */
    StoreAccess access(ReadBudget interactive, ReadBudget annotation, ReadBudget sessionWide) {
        return new StoreAccess(reader(interactive), reader(annotation), reader(sessionWide),
            graphName);
    }

    /**
     * Makes every read of {@code relation} non-terminating, so a bounded reader touching it runs out
     * of budget through the real query rather than through a threshold a case picked.
     * {@link RunawayRelation} carries the reasoning. DDL, so only on a {@link #held()} fixture.
     */
    void makeRunaway(String relation) {
        if (lifetime != Lifetime.HELD) {
            throw new IllegalStateException("makeRunaway installs a relation, and DDL on the thread's"
                + " store would change the schema every later case on this thread borrows; open this"
                + " fixture through StoreFixture.held()");
        }
        RunawayRelation.install(dsl(), relation);
    }

    /** The schema file this fixture captured, spelled as the store's {@code source_name} spells it. */
    String sourceName() {
        return SchemaSource.file(file).sourceName();
    }

    /** The scoped query surface a provider takes. */
    StoreHandle handle() {
        return new StoreHandle(dsl(), graphName);
    }

    /** The same store seen as another graph, for asserting one graph cannot read another's rows. */
    StoreHandle handleFor(String otherGraph) {
        return new StoreHandle(dsl(), otherGraph);
    }

    @Override
    public void close() {
        captured.close();
    }
}
