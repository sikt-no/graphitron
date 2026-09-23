package no.sikt.graphitron.model.test;

import graphql.schema.idl.TypeDefinitionRegistry;
import no.sikt.graphitron.model.boot.GraphitronModelStore;
import no.sikt.graphitron.model.boot.ReadBudget;
import no.sikt.graphitron.model.boot.StoreReader;
import no.sikt.graphitron.model.capture.code.ClasspathSourceCapture;
import no.sikt.graphitron.model.capture.code.CodeCapture;
import no.sikt.graphitron.model.capture.code.JvmCapture;
import no.sikt.graphitron.model.capture.document.GraphQLSchemaProblems;
import no.sikt.graphitron.model.config.ClasspathEntry;
import no.sikt.graphitron.model.run.GraphIdentity;
import no.sikt.graphitron.model.run.SubjectConfig;
import no.sikt.graphitron.model.classpath.CompletionData;
import no.sikt.graphitron.model.schema.SchemaAssembly;
import no.sikt.graphitron.model.schema.SchemaError;
import no.sikt.graphitron.model.schema.SchemaLoader;
import no.sikt.graphitron.model.schema.input.SchemaInput;
import no.sikt.graphitron.model.schema.input.SchemaInputAttribution;
import no.sikt.graphitron.model.schema.input.SchemaRecipe;
import no.sikt.graphitron.model.schema.input.SchemaSource;
import no.sikt.graphitron.model.run.ModelCapture;
import org.jooq.DSLContext;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import no.sikt.graphitron.model.jooq.JooqCatalog;

/**
 * A booted fact store with one or more SDL fixtures captured into it: the capture-level population,
 * for the tests whose subject is what a real capture writes.
 *
 * <p><b>Which harness is this.</b> Rows arrive here only through the pass, {@link
 * ModelCapture} {@link ModelCapture}, so a fixture cannot encode a state capture never
 * produces. That is the property to want when the subject is capture itself, the gatherers and the
 * writers, or agreement between a store-native relation and a reader above.
 *
 * <p>When the subject is instead what a relation <em>returns given rows</em>, a view's
 * joins or a check constraint's boundary, {@link SeededStore} beside this one states the inputs as
 * rows with no pipeline in the way. Seeding skips the step capture exists to perform, so a fixture
 * here that hand-inserts rows owes a reason at the call site.
 *
 * <p>Lives here rather than in the generator's tests because every arm on it is a capture, and
 * capture is this module's. The one arm that was not, a capture behind the generator's own
 * attribution pipeline, stayed above the line with the two tests that read it.
 *
 * <p><b>Layered.</b> {@link #withCapturedStore} is the closure form and the shortest thing to type;
 * this handle is the primitive underneath it, for a test that needs more than one step against the
 * open store; and {@link #registryOf} / {@link #attributionOf} / {@link #fixtureFile} / {@link #graph}
 * are the primitives under that, for a test whose axis combination no factory names and which drives
 * the pass itself.
 *
 * <p><b>Named arms, not flags.</b> Each factory says in its own name what its shape carries. The
 * classpath census is an argument rather than an axis, because it pairs with every shape and naming
 * it would double the set to say nothing.
 *
 * <p>Owns the store's lifetime so a test can query after capture, step by step rather than inside
 * one continuation. The store itself comes from {@link FactStores#inMemory()} rather than being
 * booted here, so how a store is stood up is stated in one place beside the schema that declares
 * it.
 */
public final class CapturedStore implements AutoCloseable {

    /**
     * The graph a fixture captures under unless a test names a second one. Both downstream fixtures
     * arrived at this same value independently, and it is also what keys the fixture's filename, so
     * the default spelling on disk is {@code fixture.graphqls}.
     */
    public static final String GRAPH = "fixture";

    private final GraphitronModelStore store;
    private final String graphName;
    private final Path directory;
    private final Path file;
    private final TypeDefinitionRegistry registry;

    /**
     * Whether this fixture booted its own store or borrowed the thread's, which is all
     * {@link #close()} has to decide between. A case that rewrites the schema owns one; see
     * {@link #ownStore}.
     */
    private final boolean owned;

    /** The borrow this fixture took, or -1 when it owns its store. See {@link #mine()}. */
    private final long generation;

    private CapturedStore(GraphitronModelStore store, String graphName, Path directory, Path file,
                          TypeDefinitionRegistry registry) {
        this(store, graphName, directory, file, registry, false);
    }

    private CapturedStore(GraphitronModelStore store, String graphName, Path directory, Path file,
                          TypeDefinitionRegistry registry, boolean owned) {
        this.owned = owned;
        this.generation = owned ? -1 : ThreadConfinedStore.generation();
        this.store = store;
        this.graphName = graphName;
        this.directory = directory;
        this.file = file;
        this.registry = registry;
    }

    // ---------------------------------------------------------------------------------------
    // The closure form: hand it SDL, get a DSLContext, assert.
    // ---------------------------------------------------------------------------------------

    /** Captures {@code sdl} under {@link #GRAPH} and runs {@code body} against the open store. */
    public static void withCapturedStore(Path directory, String sdl, Consumer<DSLContext> body) {
        withCapturedStore(directory, GRAPH, sdl, body);
    }

    /** The same under a graph the caller names, for the cases whose subject is the partition. */
    public static void withCapturedStore(Path directory, String graphName, String sdl,
                                         Consumer<DSLContext> body) {
        Path file = write(directory, graphName, sdl);
        var registry = SchemaLoader.load(List.of(SchemaSource.file(file)));
        SeededStore.withSeededStore(dsl -> {
            captureFiles(dsl, List.of(file), directory, graphName, registry, null, List.of(), false);
            body.accept(dsl);
        });
    }

    // ---------------------------------------------------------------------------------------
    // The handle: one factory per capture shape.
    // ---------------------------------------------------------------------------------------

    /**
     * {@link #of(Path, String)} on a store of this fixture's own rather than the thread's.
     *
     * <p>For a case that changes the schema. A clear puts rows back and cannot put a relation back,
     * so a case that drops a table, or demotes a stage-written table to its rule the way a cost
     * comparison does, would leave every later case on that thread looking at a different
     * store. That is the rule the funnel already applies to the gate classes that issue DDL, and
     * this is how a fixture obeys it.
     *
     * <p>Such a case pays for a boot, which is the honest price of the schema it rewrites.
     */
    public static CapturedStore ownStore(Path directory, String graphName, String sdl) {
        Path file = write(directory, graphName, sdl);
        var registry = SchemaLoader.load(List.of(SchemaSource.file(file)));
        var store = FactStores.inMemory();
        captureFiles(store.dsl(), List.of(file), directory, graphName, registry, null, List.of(), false);
        return new CapturedStore(store, graphName, directory, file, registry, true);
    }

    /**
     * {@link #ofCatalog(Path, String, JooqCatalog)} on a store of this fixture's own, for the same
     * reason {@link #ownStore} exists: the cases that reach for this install their own relations or
     * demote registered ones to views, and a clear cannot undo either.
     */
    public static CapturedStore ownStoreOfCatalog(Path directory, String sdl, JooqCatalog jooq) {
        Path file = write(directory, GRAPH, sdl);
        var registry = SchemaLoader.load(List.of(SchemaSource.file(file)));
        var store = FactStores.inMemory();
        captureFiles(store.dsl(), List.of(file), directory, GRAPH, registry, jooq, List.of(), false);
        return new CapturedStore(store, GRAPH, directory, file, registry, true);
    }

    /** {@link #ofCatalog(Path, String, String, JooqCatalog, List)} on a store of its own. */
    public static CapturedStore ownStoreOfCatalog(Path directory, String graphName, String sdl,
                                                  JooqCatalog jooq,
                                                  List<CompletionData.ExternalReference> census) {
        Path file = write(directory, graphName, sdl);
        var registry = SchemaLoader.load(List.of(SchemaSource.file(file)));
        var store = FactStores.inMemory();
        captureFiles(store.dsl(), List.of(file), directory, graphName, registry, jooq, census, false);
        return new CapturedStore(store, graphName, directory, file, registry, true);
    }

    /** {@link #ofCatalog(Path, String, String, JooqCatalog)} on a store of its own. */
    public static CapturedStore ownStoreOfCatalog(Path directory, String graphName, String sdl,
                                                  JooqCatalog jooq) {
        return ownStoreOfCatalog(directory, graphName, sdl, jooq, List.of());
    }

    /** {@link #of(Path, String, String, List)} on a store of its own. */
    public static CapturedStore ownStore(Path directory, String graphName, String sdl,
                                         List<CompletionData.ExternalReference> census) {
        return ownStoreOfCatalog(directory, graphName, sdl, null, census);
    }

    /** {@link #ofFiles(Path, String, String, String, String)} on a store of its own. */
    public static CapturedStore ownStoreOfFiles(Path directory, String firstName, String firstSdl,
                                                String secondName, String secondSdl) {
        List<Path> files = List.of(write(directory, firstName, firstSdl),
            write(directory, secondName, secondSdl));
        var registry = SchemaLoader.load(files.stream().map(SchemaSource::file).toList());
        var store = FactStores.inMemory();
        captureFiles(store.dsl(), files, directory, GRAPH, registry, null, List.of(), false);
        return new CapturedStore(store, GRAPH, directory, files.getFirst(), registry, true);
    }

    /** {@link #ownStore(Path, String, String)} under the default graph. */
    public static CapturedStore ownStore(Path directory, String sdl) {
        return ownStore(directory, GRAPH, sdl);
    }

    /** Captures {@code sdl} alone: the shape for the arms answered by SDL-derived facts. */
    public static CapturedStore of(Path directory, String sdl) {
        return of(directory, GRAPH, sdl);
    }

    /** The same under a graph the caller names, which is what a partition assertion needs two of. */
    public static CapturedStore of(Path directory, String graphName, String sdl) {
        return of(directory, graphName, sdl, List.of());
    }

    /**
     * The same plus a classpath census, for the arms that read the {@code jvm_} families. The census
     * is the caller's own scan rather than a shape of this handle: what a rule reading a class's
     * declared form is worth depends on the classes being real ones.
     */
    public static CapturedStore of(Path directory, String graphName, String sdl,
                                   List<CompletionData.ExternalReference> census) {
        return openAndCapture(directory, graphName, sdl, null, census);
    }

    /**
     * Captures two documents into one graph, which is what a graph assembled from more than one
     * schema file looks like: one parse over both, one capture, two source memberships.
     * {@link #file()} is the first, so a case asks from that document and answers out of the other.
     *
     * <p>Two rather than a list, because two is what the shape claims: a graph whose answer lives in
     * a file other than the one the question came from. A third document repeats the claim.
     */
    public static CapturedStore ofFiles(Path directory, String firstName, String firstSdl,
                                        String secondName, String secondSdl) {
        return ofFiles(directory, firstName, firstSdl, secondName, secondSdl, null);
    }

    /**
     * {@link #ofFiles(Path, String, String, String, String)} against a generated jOOQ catalog, for a
     * case whose two arms are the same two documents with and without one.
     */
    public static CapturedStore ofFiles(Path directory, String firstName, String firstSdl,
                                        String secondName, String secondSdl, JooqCatalog jooq) {
        List<Path> files = List.of(write(directory, firstName, firstSdl),
            write(directory, secondName, secondSdl));
        var registry = SchemaLoader.load(files.stream().map(SchemaSource::file).toList());
        var store = ThreadConfinedStore.borrow();
        captureFiles(store.dsl(), files, directory, GRAPH, registry, jooq, List.of(), false);
        return new CapturedStore(store, GRAPH, directory, files.getFirst(), registry);
    }

    /**
     * Captures {@code sdl} against a generated jOOQ catalog: the shape for the arms whose answer
     * involves a table, a column or a key, none of which a schema alone declares.
     *
     * <p>The catalog is capture's only catalog-shaped input, so there is no inference axis to arm.
     * Nodehood is derived from the captured facts of both corpora rather than decided during the
     * walk, so a bare arm above differs from this one only in whether the catalog facts are in the
     * store to derive from; what the derivation makes of them is the shadow tests' subject in the
     * generator's own module and not an axis a fixture arms.
     */
    public static CapturedStore ofCatalog(Path directory, String sdl, JooqCatalog jooq) {
        return ofCatalog(directory, GRAPH, sdl, jooq);
    }

    /** {@link #ofCatalog(Path, String, JooqCatalog)} under a graph the caller names. */
    public static CapturedStore ofCatalog(Path directory, String graphName, String sdl, JooqCatalog jooq) {
        return ofCatalog(directory, graphName, sdl, jooq, List.of());
    }

    /** The catalog shape plus a classpath census, for a test whose arms span both. */
    public static CapturedStore ofCatalog(Path directory, String graphName, String sdl, JooqCatalog jooq,
                                          List<CompletionData.ExternalReference> census) {
        return openAndCapture(directory, graphName, sdl, Objects.requireNonNull(jooq, "jooq"), census);
    }

    /**
     * The same, with {@code classRoot} read as this build's own output, for a fixture whose
     * assertions reach a relation the code family feeds: a condition hop routes off what
     * {@code @condition} may name, which is the code family's statement and not the census's.
     *
     * <p>The census argument and this one describe the same directory and are not redundant: one is
     * the walk's transcription of the classpath and the other is the reading {@link ModelCapture}
     * performs over it, and a run does both.
     */
    public static CapturedStore ofCatalog(Path directory, String graphName, String sdl, JooqCatalog jooq,
                                          List<CompletionData.ExternalReference> census,
                                          Path classRoot) {
        return openAndCapture(directory, graphName, sdl, Objects.requireNonNull(jooq, "jooq"), census,
            List.of(new ClasspathEntry(classRoot, ClasspathEntry.Origin.PROJECT, null, null)));
    }

    /**
     * A graph whose only source refused: {@code refusedSdl} is spelled so a stage objects to it, and
     * nothing else was read. The shape for a case about what an editor shows in the file the author
     * has just broken, where there is no surviving document to fall back on.
     */
    public static CapturedStore ofRefusedSchema(Path directory, String refusedSdl) {
        return captureRefused(directory, List.of(write(directory, GRAPH, refusedSdl)), null);
    }

    /**
     * A graph whose newest read refused something: {@code sdl} parses and is transcribed, and
     * {@code refusedSdl} is spelled so a stage objects to it, whether the parser (a source it cannot
     * read) or the registry (a declaration it will not admit beside the first one's).
     *
     * <p>The pair with a catalog beside it, where the one-argument arm above is the graph that lost
     * its only source. Both capture a verdict rather than a registry alone, which is what a case
     * about a store whose last read failed has to have in it: the refusal row is what makes the read
     * not-clean, and any surviving source's coordinates are what a reader goes on answering from
     * while it is. The verdict is a {@code graphql_schema_problem} row, which is where all three
     * reading stages record one.
     */
    public static CapturedStore ofRefusedSchema(Path directory, String sdl, String refusedSdl,
                                                JooqCatalog jooq) {
        Objects.requireNonNull(jooq, "jooq");
        return captureRefused(directory, List.of(write(directory, GRAPH, sdl),
            write(directory, GRAPH + "-refused", refusedSdl)), jooq);
    }

    /**
     * Captures {@code files}, one of which is spelled so a reading stage objects to it.
     *
     * <p>Parsed here as well as by the pass, and only to fail when nothing objected: an arm whose
     * refused source quietly started parsing would otherwise go on passing as a fixture for a
     * refusal. The registry this hands back is the same reason, a caller wanting to know what did
     * parse.
     *
     * <p>Nothing writes the verdict beside the pass any more. Both stages that can refuse record
     * their own rows: the reader writes what would not parse and the assembly writes what would not
     * compose, so the store a refusal leaves is the store a run leaves.
     */
    private static CapturedStore captureRefused(Path directory, List<Path> files, JooqCatalog jooq) {
        var parse = SchemaLoader.parsePerSource(files.stream().map(SchemaSource::file).toList());
        if (parse.failures().isEmpty() && parse.registryErrors().isEmpty()) {
            throw new AssertionError("nothing objected to " + files.getLast().getFileName()
                + "; this arm's whole subject is a read that refused something");
        }
        var store = ThreadConfinedStore.borrow();
        captureFiles(store.dsl(), files, directory, GRAPH, parse.registry(), jooq, List.of(), false);
        return new CapturedStore(store, GRAPH, directory, files.getFirst(), parse.registry());
    }

    private static CapturedStore openAndCapture(Path directory, String graphName, String sdl,
                                                JooqCatalog jooq,
                                                List<CompletionData.ExternalReference> census) {
        return openAndCapture(directory, graphName, sdl, jooq, census, List.of());
    }

    private static CapturedStore openAndCapture(Path directory, String graphName, String sdl,
                                                JooqCatalog jooq,
                                                List<CompletionData.ExternalReference> census,
                                                List<ClasspathEntry> classpath) {
        Path file = write(directory, graphName, sdl);
        var registry = SchemaLoader.load(List.of(SchemaSource.file(file)));
        var store = ThreadConfinedStore.borrow();
        captureFile(store, file, directory, graphName, registry, jooq, census, false, classpath);
        return new CapturedStore(store, graphName, directory, file, registry);
    }

    // ---------------------------------------------------------------------------------------
    // Further captures into a store that is already open.
    // ---------------------------------------------------------------------------------------

    /**
     * Captures a second graph, over a schema file of its own, into this same store: the shape for a
     * case asserting that one graph's scope cannot reach another's rows.
     */
    public CapturedStore andGraph(String otherGraph, String sdl) {
        return andGraph(otherGraph, sdl, List.of());
    }

    /** {@link #andGraph(String, String)} with a classpath census on the second graph. */
    public CapturedStore andGraph(String otherGraph, String sdl,
                                  List<CompletionData.ExternalReference> census) {
        captureAnother(otherGraph, sdl, null, census, false);
        return this;
    }

    /** {@link #andGraph(String, String)} against a generated jOOQ catalog. */
    public CapturedStore andCatalogGraph(String otherGraph, String sdl, JooqCatalog jooq) {
        return andCatalogGraph(otherGraph, sdl, jooq, List.of());
    }

    /** The same with a classpath census, for a sweep whose relations span the catalog and the census. */
    public CapturedStore andCatalogGraph(String otherGraph, String sdl, JooqCatalog jooq,
                                         List<CompletionData.ExternalReference> census) {
        captureAnother(otherGraph, sdl, Objects.requireNonNull(jooq, "jooq"), census, false);
        return this;
    }

    /**
     * Captures a second graph over the <em>same</em> schema file this fixture already captured, which
     * is the shared-file case: one document, two memberships, both true. Keying the filename on the
     * graph name is what leaves this expressible; a directory per graph would make the file's
     * location a function of the graph and could not state it at all.
     */
    public CapturedStore andGraphSharingTheFile(String otherGraph) {
        captureFile(store, file, directory, otherGraph,
            SchemaLoader.load(List.of(SchemaSource.file(file))), null, List.of(), false);
        return this;
    }

    /**
     * Captures this graph again, the way a dev session's next build does: warm, so the previous
     * round's rows for this graph are stood down before the new ones land rather than accumulating
     * beside them.
     */
    public CapturedStore recapture(String sdl) {
        captureAnother(graphName, sdl, null, List.of(), true);
        return this;
    }

    /**
     * {@link #recapture(String)} against a generated jOOQ catalog, which is a consumer pointing their
     * codegen somewhere else between two builds. The graph's source membership is rewritten whole, so
     * what its scope sees afterwards is the new catalog alone.
     */
    public CapturedStore recaptureCatalog(String sdl, JooqCatalog jooq) {
        captureAnother(graphName, sdl, Objects.requireNonNull(jooq, "jooq"), List.of(), true);
        return this;
    }

    private void captureAnother(String graph, String sdl, JooqCatalog jooq,
                                List<CompletionData.ExternalReference> census, boolean warm) {
        Path other = write(directory, graph, sdl);
        captureFile(store, other, directory, graph,
            SchemaLoader.load(List.of(SchemaSource.file(other))), jooq, census, warm);
    }

    private static void captureFile(GraphitronModelStore store, Path file, Path directory,
                                    String graphName, TypeDefinitionRegistry registry, JooqCatalog jooq,
                                    List<CompletionData.ExternalReference> census, boolean warm) {
        captureFiles(store.dsl(), List.of(file), directory, graphName, registry, jooq, census, warm);
    }

    private static void captureFile(GraphitronModelStore store, Path file, Path directory,
                                    String graphName, TypeDefinitionRegistry registry, JooqCatalog jooq,
                                    List<CompletionData.ExternalReference> census, boolean warm,
                                    List<ClasspathEntry> classpath) {
        captureFiles(store.dsl(), List.of(file), directory, graphName, registry, jooq, census, warm,
            classpath);
    }

    /**
     * One reading of the documents, through {@link ModelCapture}, which is what a run captures
     * through.
     *
     * <p>This level promises that a fixture cannot encode a state capture never writes. It used to
     * keep half of it, because the fixture drove a walk of its own beside the pass and the two
     * wrote overlapping families, so a reader repointed at an entry found an empty relation in
     * every test while a real run had the rows all along. There is one writer now and the promise
     * is whole: what a fixture holds is what the pass writes, or the pass did not write it.
     *
     * <p>The anchor first, then the pass. {@link ModelCapture#writeGraph} states the row every
     * other relation hangs a key on, and a fixture driving the gatherers itself owes it before any
     * of them rather than spelling the upsert a second time.
     *
     * <p>Each graph names its own files rather than matching a pattern under the directory: the
     * arms capturing a second graph write its file beside the first, and a glob would hand each
     * graph the other's source.
     */
    private static void captureFiles(DSLContext dsl, List<Path> files, Path directory,
                                     String graphName, TypeDefinitionRegistry registry, JooqCatalog jooq,
                                     List<CompletionData.ExternalReference> census, boolean warm) {
        captureFiles(dsl, files, directory, graphName, registry, jooq, census, warm, List.of());
    }

    /**
     * The same capture with a classpath, for a fixture whose assertions reach a relation the code
     * family feeds. The census parameter beside it is the walk's transcription of a classpath and
     * this is the code family's reading of one, so a fixture supplying only the first gets a store
     * where a class is in the census and no directive may name it, which is not a state a run
     * produces.
     *
     * <p>The code family is written before the walk, which is the order a run has and the order
     * that matters: {@code AbstractRewriteMojo.runGenerator} captures the model into the store and
     * then invokes the generator, whose walk derives over what it finds there. The walk materialises
     * as it goes, so a code row written after it is invisible to everything derived during it, and a
     * fixture would read a resolved route beside a chain that never saw it.
     */
    @SuppressWarnings("removal")  // states a census while fixtures without a classpath do
    private static void captureFiles(DSLContext dsl, List<Path> files, Path directory,
                                     String graphName, TypeDefinitionRegistry registry, JooqCatalog jooq,
                                     List<CompletionData.ExternalReference> census, boolean warm,
                                     List<ClasspathEntry> classpath) {
        var readAt = LocalDateTime.now();
        // The graph's own row, before anything that keys into it. The pass writes it first thing,
        // but the stated census below runs ahead of the pass and claims sources against it.
        ModelCapture.writeGraph(dsl, new GraphIdentity(graphName, directory), readAt);
        if (classpath.isEmpty()) {
            // Before the pass, because the pass derives at its tail and those derivations read
            // this family. A fixture stating a census and naming no classpath has nothing for the
            // classpath gatherer to scan, so this is where those rows come from.
            ClasspathSourceCapture.stated(dsl, graphName,
                census.stream().map(CompletionData.ExternalReference::sourceName).toList(), readAt);
            JvmCapture.captureStated(dsl, census, readAt);
            // And the same statement as the reading would have written it. Both families, because a
            // census is a statement about a classpath and a run reads that classpath once into each:
            // a fixture that stated only the walk's transcription would leave every rule that moved
            // to the reading answering nothing, which is a fixture's silence and not a rule's.
            CodeRows.writeStated(dsl, census, readAt);
        }
        // The pass, and the whole of the capture: it writes the graph row, the catalog, the
        // classpath families where there is a classpath, every document stratum, and runs the
        // derivations at its tail.
        ModelCapture.capture(dsl, new GraphIdentity(graphName, directory),
            corpusOf(files, directory), classpath, jooq, readAt);
    }

    /**
     * The corpus the pass reads, stated as the configuration a run would have had.
     *
     * <p>A merged registry used to be the whole of what a capture needed, and a pass given one took
     * {@code SubjectConfig.none()} because nothing in it asked where the documents were. The
     * document gatherer does: its rows are keyed by the position a node was written at in one file,
     * which a merged registry cannot say. So the corpus is named here rather than merged away,
     * which is what the arm below turns on.
     *
     * <p>Literal bindings rather than a glob over the directory, so the corpus is exactly the files
     * this capture was given. Several arms here write more than one file into one directory and
     * then capture one of them, and a pattern would quietly hand the gatherer the other.
     */
    public static SubjectConfig corpusOf(List<Path> files, Path directory) {
        return SubjectConfig.of(new SchemaRecipe(directory.resolve("pom.xml"),
            files.stream().map(file -> SchemaRecipe.Binding.literal(SchemaSource.file(file)))
                .toList(),
            List.of("graphqls")));
    }

    // ---------------------------------------------------------------------------------------
    // The primitives, for a test that drives the pass itself.
    // ---------------------------------------------------------------------------------------

    /**
     * One capture of {@code graph}'s corpus, by the pass a run drives.
     *
     * <p>For a test whose axis combination no factory above names, so that such a test states which
     * corpus and which catalog rather than which arity. The instant is taken here because a test
     * driving one capture has no earlier moment to date it by; an arm wanting two captures to be
     * told apart calls {@link ModelCapture#capture} with instants of its own.
     */
    public static void capture(DSLContext dsl, GraphIdentity graph, SubjectConfig corpus,
                               JooqCatalog jooq) {
        capture(dsl, graph, corpus, jooq, List.of());
    }

    /** {@link #capture(DSLContext, GraphIdentity, SubjectConfig, JooqCatalog)} with a classpath. */
    public static void capture(DSLContext dsl, GraphIdentity graph, SubjectConfig corpus,
                               JooqCatalog jooq, List<ClasspathEntry> classpath) {
        ModelCapture.capture(dsl, graph, corpus, classpath, jooq,
            LocalDateTime.now().truncatedTo(ChronoUnit.MICROS));
    }

    /**
     * Writes what the three reading stages refused, for a test that drove the walk itself and wants
     * the verdict beside what the walk transcribed.
     *
     * <p>A primitive rather than a step inside the walk, because the two are on either side of the
     * migration: the walk writes the transcription families and {@link GraphQLSchemaProblems} writes the
     * one relation all three stages record in. Reached directly rather than through the document
     * gatherers, whose anchor step sweeps every anchor row carrying an instant other than
     * its own and would therefore delete what the walk had just written, and called after the walk,
     * the verdict row referencing a graph whose anchor row is the walk's to write.
     */
    public static void writeSchemaProblems(DSLContext dsl, String graphName,
                                           SchemaLoader.PerSourceParse parse,
                                           SchemaAssembly assembly) {
        var raised = new ArrayList<SchemaError>(parse.registryErrors());
        raised.addAll(assembly.errors());
        var at = LocalDateTime.now();
        // Two calls because the relation numbers and sweeps per stage, so each stage's writer
        // stands alone; a fixture driving the walk plays both of them.
        GraphQLSchemaProblems.writeParsed(dsl, graphName, parse.failures(), at);
        GraphQLSchemaProblems.writeAssembled(dsl, graphName, List.copyOf(raised), at);
    }

    /**
     * Writes what a schema may name in the Java on one classpath entry, for a test that drove the
     * walk and wants the code family beside what the walk transcribed.
     *
     * <p>A primitive for the same reason {@link #writeSchemaProblems} is one: the two writers are
     * on either side of the migration. The walk transcribes the classpath as a census, {@link
     * CodeCapture} states what one directive may name, and a fixture whose assertions span both
     * reaches each directly. The entry is this build's own output, which is what a fixture
     * pointing at its own test classes is saying.
     *
     * <p>The graph is linked to the entry by whoever registered it; this writes the entry's rows,
     * not the reading that claimed it for a graph.
     */
    public static void captureCode(DSLContext dsl, Path entry, String skipPrefix,
                                   ClassLoader loader) {
        var readAt = LocalDateTime.now();
        var classpath = List.of(new ClasspathEntry(entry, ClasspathEntry.Origin.PROJECT, null, null));
        CodeCapture.capture(dsl,
            ClasspathSourceCapture.read(dsl, classpath, skipPrefix, readAt), loader, readAt);
    }

    /** The graph identity a fixture captured under, shared so readers can scope by it. */
    public static GraphIdentity graph(Path directory) {
        return graph(directory, GRAPH);
    }

    /** {@link #graph(Path)} for a graph the caller names. */
    public static GraphIdentity graph(Path directory, String graphName) {
        return new GraphIdentity(graphName, directory);
    }

    /**
     * Writes the fixture's schema file and says where it landed.
     *
     * <p>What a caller driving the pass wants of {@link #registryOf}: the pass reads the corpus
     * itself, so the parse that arm also performs is a second reading nobody asked for.
     */
    public static Path writeSource(Path directory, String sdl) {
        return write(directory, GRAPH, sdl);
    }

    /** {@link #writeSource(Path, String)} under a graph the caller names. */
    public static Path writeSource(Path directory, String graphName, String sdl) {
        return write(directory, graphName, sdl);
    }

    /** Just the parse, for callers that fill a store from something other than the SDL. */
    public static TypeDefinitionRegistry registryOf(Path directory, String sdl) {
        return registryOf(directory, GRAPH, sdl);
    }

    /** {@link #registryOf(Path, String)} writing the fixture under a graph the caller names. */
    public static TypeDefinitionRegistry registryOf(Path directory, String graphName, String sdl) {
        return SchemaLoader.load(List.of(SchemaSource.file(write(directory, graphName, sdl))));
    }

    /**
     * The attribution map over the one input {@link #registryOf} minted, so capture's stamp lookup
     * resolves the fixture's schema file instead of meeting a name no input declared. Derived from
     * {@link #fixtureFile} like the load is, so the two cannot disagree about what was handed over.
     */
    public static Map<String, SchemaInput> attributionOf(Path directory) {
        return attributionOf(directory, GRAPH);
    }

    /** {@link #attributionOf(Path)} for a graph the caller names. */
    public static Map<String, SchemaInput> attributionOf(Path directory, String graphName) {
        return attributionOfFiles(List.of(fixtureFile(directory, graphName)));
    }

    /**
     * The attribution map for a fixture that handed the loader exactly {@code files}, as capture's
     * stamp lookup needs it: a source name the map does not resolve is a gap between the run's inputs
     * and what the parser handed back, and capture refuses to guess at one. Every file is a
     * {@code file} arm, because a source that reached a real parse necessarily is one.
     */
    private static Map<String, SchemaInput> attributionOfFiles(List<Path> files) {
        return SchemaInputAttribution.build(files.stream().map(SchemaInput::file).toList());
    }

    /**
     * Where a graph's fixture is written; the load and the attribution share it. Keyed on the graph
     * name rather than fixed, because the path a fixture is written to <em>is</em> its identity
     * downstream: it is the string the parser is handed, the one graphql-java echoes back as a source
     * name, and the key capture's stamp lookup is read on. One fixed name would have each capture into
     * a directory silently overwrite the last, the load still succeeding against whatever text landed
     * there.
     */
    public static Path fixtureFile(Path directory) {
        return fixtureFile(directory, GRAPH);
    }

    /**
     * The corpus {@link #registryOf} wrote, stated as the configuration a run would have had, for a
     * test that drives the pass itself.
     *
     * <p>A pass used to need only the merged registry, so these tests handed it
     * {@code SubjectConfig.none()} and lost nothing. It runs the document gatherer now, whose rows
     * are keyed by a position in one file and which therefore re-reads the corpus from
     * configuration, so a pass given no corpus writes none of that gatherer's relations. Silent
     * until one of them is a relation the pass used to write itself.
     */
    public static SubjectConfig corpusOf(Path directory) {
        return corpusOf(directory, GRAPH);
    }

    /** {@link #corpusOf(Path)} for a graph the caller named, which names the fixture's file. */
    public static SubjectConfig corpusOf(Path directory, String graphName) {
        return corpusOf(List.of(fixtureFile(directory, graphName)), directory);
    }

    /** {@link #fixtureFile(Path)} for a graph the caller names. */
    public static Path fixtureFile(Path directory, String graphName) {
        return directory.resolve(graphName + ".graphqls");
    }

    private static Path write(Path directory, String graphName, String sdl) {
        Path file = fixtureFile(directory, graphName);
        try {
            Files.createDirectories(directory);
            Files.writeString(file, sdl);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return file;
    }

    // ---------------------------------------------------------------------------------------
    // Reads.
    // ---------------------------------------------------------------------------------------

    /**
     * Fails if the thread has lent this fixture's store to somebody else since.
     *
     * <p>A borrow belongs to the case that took it, and the next one reclaims rather than refuses,
     * which is what stops a missed close failing unrelated cases. The cost of that is a fixture
     * outliving its case would find its rows cleared and read an empty store as an answer. This is
     * what makes that a failure instead: a class holding one fixture across its cases hears about
     * it here rather than in an assertion about something else.
     */
    private void mine() {
        if (!owned && generation != ThreadConfinedStore.generation()) {
            throw new IllegalStateException("this CapturedStore borrowed the thread's store and"
                + " another case has since borrowed it, which cleared the rows this one captured."
                + " A fixture a class holds across its cases wants CapturedStore.ownStore, which"
                + " boots one of its own; a fixture built per case wants building inside the case");
        }
    }

    public DSLContext dsl() {
        mine();
        return store.dsl();
    }

    public TypeDefinitionRegistry registry() {
        return registry;
    }

    /** The graph this fixture captured under. */
    public String graphName() {
        return graphName;
    }

    /** The schema file this fixture captured, for a reader that needs the name the store spells. */
    public Path file() {
        return file;
    }

    /**
     * A reader of this store, for the cases whose subject is the read boundary rather than a query.
     *
     * <p>Unbounded, and every fixture reader in the reactor says the same thing for the same reason:
     * a harness that named a number would be asserting a wall-clock threshold in a tier that must
     * not fail for being slow. The arm says "no budget" structurally instead.
     */
    public StoreReader reader() {
        return reader(new ReadBudget.Unbounded());
    }

    /** A reader under a stated budget, for the cases whose subject <em>is</em> the budget. */
    public StoreReader reader(ReadBudget budget) {
        mine();
        return store.reader(budget);
    }

    @Override
    public void close() {
        if (owned) {
            store.close();
        } else {
            ThreadConfinedStore.release();
        }
    }
}
