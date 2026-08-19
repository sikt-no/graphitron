package no.sikt.graphitron.lsp;

import no.sikt.graphitron.model.boot.GraphitronModelStore;
import no.sikt.graphitron.lsp.parsing.LspVocabulary;
import no.sikt.graphitron.model.boot.StoreReader;
import no.sikt.graphitron.model.read.StoreHandle;
import no.sikt.graphitron.rewrite.BuildWarning;
import no.sikt.graphitron.rewrite.GraphQLRewriteGenerator;
import no.sikt.graphitron.rewrite.JooqCatalog;
import no.sikt.graphitron.rewrite.RewriteContext;
import no.sikt.graphitron.rewrite.NodeDeclaration;
import no.sikt.graphitron.rewrite.capture.FactCapture;
import no.sikt.graphitron.rewrite.ValidationError;
import no.sikt.graphitron.rewrite.diagnostics.BuildWarningFacts;
import no.sikt.graphitron.rewrite.diagnostics.RejectionFacts;
import no.sikt.graphitron.rewrite.lint.LintConfig;
import no.sikt.graphitron.rewrite.capture.JavaSourceFacts;
import no.sikt.graphitron.rewrite.catalog.ClasspathScanner;
import no.sikt.graphitron.rewrite.catalog.CompletionData;
import no.sikt.graphitron.rewrite.capture.SourceWalker;
import no.sikt.graphitron.rewrite.schema.RewriteSchemaLoader;
import no.sikt.graphitron.rewrite.schema.SdlVerdicts;
import no.sikt.graphitron.rewrite.schema.input.SchemaInput;
import no.sikt.graphitron.rewrite.schema.input.SchemaInputAttribution;
import no.sikt.graphitron.rewrite.schema.input.SchemaSource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static no.sikt.graphitron.model.Tables.SQL_SCHEMA;
import static no.sikt.graphitron.model.Tables.SQL_TABLE;

/**
 * A booted fact store with one or more graphs captured into it, for the tests that read the store.
 *
 * <p>Stood up by real capture over an SDL fixture rather than by inserting rows, so a fixture cannot
 * encode a state capture never writes. The classpath census goes in the same way: capture takes the
 * class list as input today, so a test hands over the same references the projection-era fixtures
 * declared and the store ends up holding what a scan of those classes would have produced.
 *
 * <p>Owns the store's lifetime so a test can query after capture. {@link #handle} is over the store's
 * own connection rather than a reader's: what a provider needs is a scoped query surface, and the
 * reader's transaction and graph resolution are {@code StoreAccess}'s own business, tested through
 * {@link #reader()}.
 */
final class StoreFixture implements AutoCloseable {

    /** The graph every fixture captures under, unless a test needs to name a second one. */
    static final String GRAPH = "fixture";

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

    /** Scanned once; see {@link #backingClasses()}. */
    private static List<CompletionData.ExternalReference> backingClassCensus;

    /** SDL for a fixture whose whole subject is the classpath, so its schema is beside the point. */
    private static final String PLACEHOLDER_SDL = "type Query { placeholder: Int }\n";

    private final GraphitronModelStore store;
    private final String graphName;
    private final Path file;
    private final Path directory;

    private StoreFixture(GraphitronModelStore store, String graphName, Path file, Path directory) {
        this.store = store;
        this.graphName = graphName;
        this.file = file;
        this.directory = directory;
    }

    /** Captures {@code sdl} alone: the shape for arms answered by SDL-derived facts. */
    static StoreFixture of(Path directory, String sdl) {
        return of(directory, GRAPH, sdl, List.of());
    }

    /** Captures {@code sdl} plus a classpath census: the shape for the {@code jvm_} arms. */
    static StoreFixture of(Path directory, String sdl, List<CompletionData.ExternalReference> classpath) {
        return of(directory, GRAPH, sdl, classpath);
    }

    /** An SDL fixture with nothing in it, for the arms whose whole subject is the classpath. */
    static StoreFixture ofClasspath(Path directory, List<CompletionData.ExternalReference> classpath) {
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

    /** The catalog shape plus a classpath census, for a test whose arms span both. */
    static StoreFixture ofCatalog(Path directory, String sdl,
                                  List<CompletionData.ExternalReference> classpath) {
        return ofJooqPackage(directory, sdl, classpath, JOOQ_PACKAGE);
    }

    /**
     * The catalog shape over the multi-schema generated model, for the reads whose answer depends on
     * a name being ambiguous across schemas rather than on any one table's contents.
     */
    static StoreFixture ofMultiSchemaCatalog(Path directory, String sdl) {
        return ofJooqPackage(directory, sdl, List.of(), MULTI_SCHEMA_JOOQ_PACKAGE);
    }

    private static StoreFixture ofJooqPackage(Path directory, String sdl,
                                              List<CompletionData.ExternalReference> classpath,
                                              String jooqPackage) {
        Path file = write(directory, GRAPH, sdl);
        var store = GraphitronModelStore.open();
        capture(store, file, directory, GRAPH, classpath, new JooqCatalog(jooqPackage));
        return new StoreFixture(store, GRAPH, file, directory);
    }

    /**
     * Captures two schema files into one graph. The shape for the cases where the answer has to come
     * from a file other than the one the request is about: {@link #sourceName()} is the first file, so
     * a test opens that document and asserts on what the second one declared.
     */
    static StoreFixture ofFiles(Path directory, String firstName, String firstSdl,
                                String secondName, String secondSdl) {
        Path first = write(directory, firstName, firstSdl);
        Path second = write(directory, secondName, secondSdl);
        var store = GraphitronModelStore.open();
        capture(store, List.of(first, second), directory, GRAPH, List.of(), null);
        return new StoreFixture(store, GRAPH, first, directory);
    }

    static StoreFixture of(Path directory, String graphName, String sdl,
                           List<CompletionData.ExternalReference> classpath) {
        Path file = write(directory, graphName, sdl);
        var store = GraphitronModelStore.open();
        capture(store, file, directory, graphName, classpath);
        return new StoreFixture(store, graphName, file, directory);
    }

    /**
     * The census of the backing-class fixtures in {@code no.sikt.graphitron.lsp.fixtures}, as a real
     * classfile scan produced it. The arms that resolve a member name on a class-backed type read the
     * store's own rule over this census, and that rule reads a class's declared form, so a hand-built
     * reference could hand it a record whose classfile says otherwise. Scanned once per JVM: the scan
     * reads every class this module compiled, and the answer does not change between tests.
     */
    static synchronized List<CompletionData.ExternalReference> backingClasses() {
        if (backingClassCensus == null) {
            backingClassCensus = ClasspathScanner.scan(testClassesRoot(), JOOQ_PACKAGE).stream()
                .filter(reference -> reference.className().startsWith(FIXTURE_PACKAGE))
                .toList();
        }
        return backingClassCensus;
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
        Path file = write(directory, GRAPH, sdl);
        var store = GraphitronModelStore.open();
        var parse = RewriteSchemaLoader.parsePerSource(List.of(SchemaSource.file(file)));
        FactCapture.capture(store.dsl(), false, new FactCapture.GraphIdentity(GRAPH, directory),
            FactCapture.SubjectConfig.none(), parse.registry(),
            new SdlVerdicts(parse.failures(), parse.registryErrors()),
            SchemaInputAttribution.build(List.of(SchemaInput.file(file))), null, List.of(),
            new NodeDeclaration(null));
        return new StoreFixture(store, GRAPH, file, directory);
    }

    /**
     * Runs a real generator pass over {@code sdl} into a store on disk and loads the round's own
     * findings the way a dev round loads them. The shape for the cases about what an editor shows
     * after a build, where the finding has to be one the build reached rather than one a test wrote:
     * the walk's errors and the suppression-filtered warnings, through the loaders the dev goal runs
     * and in the order it runs them.
     */
    static StoreFixture ofBuild(Path directory, String sdl, LintConfig lintConfig) {
        Path file = write(directory, GRAPH, sdl);
        var ctx = new RewriteContext(
            List.of(new SchemaInput(SchemaSource.file(file), Optional.empty(), Optional.empty())),
            directory, GRAPH, directory, "fake.output", JOOQ_PACKAGE)
            .withLintConfig(lintConfig)
            .withStoreDirectory(directory);
        var output = new GraphQLRewriteGenerator(ctx).buildOutput();
        var store = GraphitronModelStore.openAt(directory);
        var identity = new FactCapture.GraphIdentity(GRAPH, directory);
        new RejectionFacts(store.dsl(), identity).write(output.walkErrors());
        new BuildWarningFacts(store.dsl(), identity).write(output.warnings());
        return new StoreFixture(store, GRAPH, file, directory);
    }

    /** Captures a second graph, over a schema file of its own, into this same store. */
    StoreFixture andGraph(Path directory, String otherGraph, String sdl,
                          List<CompletionData.ExternalReference> classpath) {
        capture(store, write(directory, otherGraph, sdl), directory, otherGraph, classpath);
        return this;
    }

    /**
     * Captures a second graph over the <em>same</em> schema file this fixture already captured, which
     * is the shared-file case: one document, two memberships, both true.
     */
    StoreFixture andGraphSharingTheFile(Path directory, String otherGraph) {
        capture(store, file, directory, otherGraph, List.of());
        return this;
    }

    private static void capture(GraphitronModelStore store, Path file, Path directory, String graphName,
                                List<CompletionData.ExternalReference> classpath) {
        capture(store, file, directory, graphName, classpath, null);
    }

    private static void capture(GraphitronModelStore store, Path file, Path directory, String graphName,
                                List<CompletionData.ExternalReference> classpath, JooqCatalog jooq) {
        capture(store, List.of(file), directory, graphName, classpath, jooq);
    }

    private static void capture(GraphitronModelStore store, List<Path> files, Path directory,
                                String graphName, List<CompletionData.ExternalReference> classpath,
                                JooqCatalog jooq) {
        var sources = files.stream().map(SchemaSource::file).toList();
        var registry = RewriteSchemaLoader.load(sources);
        var attribution = SchemaInputAttribution.build(files.stream().map(SchemaInput::file).toList());
        FactCapture.capture(store.dsl(), new FactCapture.GraphIdentity(graphName, directory),
            FactCapture.SubjectConfig.none(), registry, attribution, jooq, classpath,
            new NodeDeclaration(null));
    }

    /**
     * The FQN of the generated table class for {@code tableName}, read back out of the census rather
     * than spelled out here, so a test joining the java-source family to it cannot hard-code a
     * naming strategy the generator might not be using.
     */
    String tableClassFqn(String tableName) {
        return store.dsl().select(SQL_TABLE.CLASS_FQN)
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
        return store.dsl().select(SQL_SCHEMA.KEYS_CLASS_FQN)
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
        new JavaSourceFacts(store.dsl()).refresh(roots, new SourceWalker().walkFiles(roots));
    }

    private static Path write(Path directory, String graphName, String sdl) {
        Path path = directory.resolve(graphName + ".graphqls");
        try {
            Files.writeString(path, sdl);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return path;
    }

    /**
     * Writes {@code warnings} into this store's warning families under {@code graph}, the way a dev
     * session's build does once the linter has run. The real writer, so a fixture cannot record a row
     * shape the build never produces; the findings are the caller's, since what a rule concludes about
     * a schema is the build's subject and not this fixture's.
     */
    void withBuildWarnings(String graph, List<BuildWarning> warnings) {
        new BuildWarningFacts(store.dsl(), new FactCapture.GraphIdentity(graph, directory))
            .write(warnings);
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
        new RejectionFacts(store.dsl(), new FactCapture.GraphIdentity(graphName, directory))
            .write(errors);
    }

    /**
     * The directive vocabulary this fixture's graph declares, which for every fixture includes
     * graphitron's own bundled definitions: capture parses them alongside whatever schema it is
     * given. A test whose subject is not the vocabulary reads {@link BundledVocabulary} instead.
     */
    LspVocabulary vocabulary() {
        return LspVocabulary.load(handle());
    }

    /** A reader of this store, for the cases whose subject is the read boundary rather than a query. */
    StoreReader reader() {
        return store.reader();
    }

    /** The schema file this fixture captured, spelled as the store's {@code source_name} spells it. */
    String sourceName() {
        return SchemaSource.file(file).sourceName();
    }

    /** The scoped query surface a provider takes. */
    StoreHandle handle() {
        return new StoreHandle(store.dsl(), graphName);
    }

    /** The same store seen as another graph, for asserting one graph cannot read another's rows. */
    StoreHandle handleFor(String otherGraph) {
        return new StoreHandle(store.dsl(), otherGraph);
    }

    /** A reference to a class the scan found inside a jar. */
    static CompletionData.ExternalReference jarClass(String className, List<CompletionData.Method> methods) {
        return reference(className, methods, List.of(), "/nonexistent/lib.jar");
    }

    /**
     * A reference to a class the scan found in a compiled directory, so reactor-resident rather than
     * jar-resident. The directory has to exist, since that is how capture tells the two apart.
     */
    static CompletionData.ExternalReference reactorClass(
        Path classesDirectory, String className, List<CompletionData.Method> methods
    ) {
        return reference(className, methods, List.of(), classesDirectory.toString());
    }

    /** A class carrying {@code GraphQLScalarType} constants, jar-resident like the libraries are. */
    static CompletionData.ExternalReference scalarHolder(String className, String... fieldNames) {
        return reference(className, List.of(),
            Arrays.stream(fieldNames).map(CompletionData.ScalarConstant::new).toList(),
            "/nonexistent/scalars.jar");
    }

    static CompletionData.ExternalReference reference(
        String className, List<CompletionData.Method> methods,
        List<CompletionData.ScalarConstant> scalarConstants, String sourceName
    ) {
        return new CompletionData.ExternalReference(
            className.substring(className.lastIndexOf('.') + 1), className, "",
            methods, List.of(), scalarConstants, "CLASS", sourceName);
    }

    /**
     * A record class the scan found inside a jar, named by its components. Its declared form is what
     * decides whether a member name resolves against components or against bean accessors, so a
     * fixture standing in for a record has to say so.
     */
    static CompletionData.ExternalReference jarRecord(
        String className, CompletionData.RecordComponent... components
    ) {
        return new CompletionData.ExternalReference(
            className.substring(className.lastIndexOf('.') + 1), className, "",
            List.of(), List.of(components), List.of(), "RECORD", "/nonexistent/lib.jar");
    }

    /** One record component: the name an author writes, and the type a hover renders. */
    static CompletionData.RecordComponent component(String name, String displayType) {
        return new CompletionData.RecordComponent(name, displayType);
    }

    /** A method whose descriptor is synthesised from its parameter types, enough to key it apart. */
    static CompletionData.Method method(String name, String returnType, CompletionData.Parameter... parameters) {
        return new CompletionData.Method(
            name, returnType, "", List.of(parameters), false,
            "(" + Arrays.stream(parameters).map(CompletionData.Parameter::type)
                .reduce("", String::concat) + ")" + returnType);
    }

    static CompletionData.Parameter parameter(String name, String type) {
        return new CompletionData.Parameter(name, type, null, "");
    }

    /**
     * A no-argument method whose return type names one qualified class, which is what a producer
     * grounding an SDL type on a class needs. {@link #method} cannot stand in for it: the return type
     * it takes is the erased display form, and a package-less name cannot be compared for identity, so
     * the census carries the qualified names a declared type mentions as their own rows and that is
     * what the store's peel reads.
     */
    static CompletionData.Method producing(String name, String returnClassFqn) {
        var erased = method(name, returnClassFqn.substring(returnClassFqn.lastIndexOf('.') + 1));
        return new CompletionData.Method(
            erased.name(), erased.returnType(), erased.description(), erased.parameters(),
            erased.returnsCondition(), erased.descriptor(), erased.returnType(),
            List.of(new CompletionData.TypeRef("", returnClassFqn, "NONE")));
    }

    /**
     * A method whose declared return form differs from the erasure beside it, which is what a
     * generic return looks like in the census: the descriptor says {@code List} and the classfile's
     * signature says {@code List<Film>}. Separate from {@link #method} so a fixture that means the
     * two forms to differ has to say so.
     */
    static CompletionData.Method genericMethod(
        String name, String returnType, String declaredReturnType,
        CompletionData.Parameter... parameters
    ) {
        var erased = method(name, returnType, parameters);
        return new CompletionData.Method(
            erased.name(), erased.returnType(), erased.description(), erased.parameters(),
            erased.returnsCondition(), erased.descriptor(), declaredReturnType);
    }

    /** A parameter whose declared form differs from its erasure, on {@link #genericMethod}'s terms. */
    static CompletionData.Parameter genericParameter(String name, String type, String declaredType) {
        return new CompletionData.Parameter(name, type, null, "", declaredType);
    }

    @Override
    public void close() {
        store.close();
    }
}
