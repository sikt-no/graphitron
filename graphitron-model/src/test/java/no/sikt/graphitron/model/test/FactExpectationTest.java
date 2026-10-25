package no.sikt.graphitron.model.test;

import no.sikt.graphitron.model.test.CorpusExpectations.Block;
import org.jooq.DSLContext;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The fact examples: what each document under {@code src/test/resources/facts} says the store holds
 * for it.
 *
 * <p>A document's folder is a claim about its graph, checked against the store
 * ({@link FactDocuments.Claim}).
 * {@code scenarios} generate, and the {@code graphitron} module generates each one at
 * {@code generate-test-sources} for the tests of generated code, so a scenario never holds a
 * defect; {@code defects} never generate and always store one; {@code incremental-captures} are
 * graphs read in steps; {@code undecided} makes no claim and only shrinks. The prelude sits above
 * all four.
 *
 * <p>A neighbour of {@link CorpusExpectationTest} sharing its whole mechanism, and separate from it
 * for what the two folders are about rather than for anything mechanical. A corpus document carries
 * a classification verdict and renders into an author-facing page; a document here is about the
 * shape of the store, which is a contributor's concern. Folding them together would put store
 * plumbing on that page and make a fact example read as a claim about generated code.
 *
 * <p>What this class does not carry is the corpus's other obligations, and the absence is the point:
 * no projection operation, no documentation fragment, no verdict, no launcher-command apparatus. A
 * fact document is an SDL example and the rows it says the store holds for it.
 *
 * <p>A graph can be read in steps, one document per version of one of its files:
 * {@code name.<file>.<epoch>.graphqls}, the file a short name that only tells the files apart and
 * the epoch the second that version is read at. A claim about a second reading is a claim about the
 * store a first one left, so it is written as the sequence that produces it; and each reading runs
 * at its own instant, so a mark column says which reading last wrote a row, which is what tells a
 * row the sweep kept from one a later reading wrote again.
 *
 * <p>A document states the configuration its graph is captured under with {@code @withConfiguration},
 * which this class reads off the document's own text before the capture, since the configuration is
 * an input to the reading rather than something it finds. A stepped graph's reading takes it from
 * the files as they then stand, so a later version of a file without it is a run that stopped being
 * configured with it.
 */
class FactExpectationTest {

    /** The undecided folder's size, lowered as documents leave it and never raised. */
    private static final int UNDECIDED_CEILING = 43;

    /** Each document's claim, by its id, which is also its graph's name or its stepped graph's. */
    private static final Map<String, FactDocuments.Claim> claims = new LinkedHashMap<>();

    @TempDir
    static Path tmp;

    /** The name a stepped graph's prelude is written under, which no file of the pattern can take. */
    private static final String PRELUDE_FILE = "_prelude";

    private static CapturedStore captured;
    private static List<Block> blocks;
    private static List<String> divergences;

    /**
     * The documents read once, by graph, and the stepped ones: by graph, then by file, then by the
     * instant each version of the file is read at. A plain document beside a stepped graph of the
     * same name is a folder that does not say which it means.
     */
    private record Graphs(Map<String, CorpusDocuments.Document> plain,
                          Map<String, Map<String, TreeMap<Long, CorpusDocuments.Document>>> stepped) {

        static Graphs of(List<CorpusDocuments.Document> documents) {
            var plain = new LinkedHashMap<String, CorpusDocuments.Document>();
            var stepped = new LinkedHashMap<String, Map<String, TreeMap<Long, CorpusDocuments.Document>>>();
            for (var document : documents) {
                var step = FactDocuments.STEP.matcher(document.id() + CorpusDocuments.SUFFIX);
                if (step.matches()) {
                    stepped.computeIfAbsent(step.group(1), ignored -> new TreeMap<>())
                        .computeIfAbsent(step.group(2), ignored -> new TreeMap<>())
                        .put(Long.parseLong(step.group(3)), document);
                } else if (document.id().contains(".")) {
                    throw new AssertionError(document.id() + CorpusDocuments.SUFFIX + " is neither "
                        + "a plain document, whose name has no dot, nor a step named "
                        + "<graph>.<file>.<epoch>" + CorpusDocuments.SUFFIX + " with a lower-case "
                        + "file and a ten-digit epoch");
                } else {
                    plain.put(document.id(), document);
                }
            }
            for (String graph : stepped.keySet()) {
                if (plain.containsKey(graph)) {
                    throw new AssertionError(graph + " is both a document and a graph read in "
                        + "steps; name it one way");
                }
            }
            if (plain.isEmpty()) {
                throw new AssertionError("the fact folder holds no plain document to open the store");
            }
            return new Graphs(plain, stepped);
        }

        /** Every instant one stepped graph is read at, in order. */
        static List<Long> instants(Map<String, TreeMap<Long, CorpusDocuments.Document>> files) {
            return files.values().stream().flatMap(versions -> versions.keySet().stream())
                .distinct().sorted().toList();
        }
    }

    @BeforeAll
    static void captureEveryDocument() {
        var ctx = TestRunContext.of();
        var jooq = CatalogInput.of(ctx);
        var claimed = FactDocuments.claimed();
        // -Dfacts=<prefix>[,<prefix>...] reads only the documents named by one, for iterating on a
        // document without capturing the rest. A build sets nothing and reads them all.
        var only = System.getProperty("facts");
        if (only != null) {
            var prefixes = List.of(only.split(","));
            claimed.keySet().removeIf(document -> prefixes.stream().noneMatch(document.id()::startsWith));
        }
        claimed.forEach((document, claim) -> claims.put(document.id(), claim));
        var graphs = Graphs.of(List.copyOf(claimed.keySet()));
        // The classpath corpus beside the catalog, so a document can state what a reading finds in
        // Java as well as what it finds in SDL. The model and the capture are one subject: a
        // document asserting a derived relation without the facts under it tests half of what it
        // names, and the runner resolves a relation by name whether capture wrote it or a view
        // derived it.
        //
        // Affordable because the classpath is read once for the store. It carries no graph, so the
        // second document's reading of it is the first document's reading again.
        var corpus = ClasspathCorpus.entries();
        var allBlocks = new ArrayList<Block>();
        var allDivergences = new ArrayList<String>();

        for (var document : graphs.plain().values()) {
            captured = FactDocuments.capture(captured, tmp, document, jooq, corpus);
        }
        var plainBlocks = CorpusExpectations.blocks(captured.dsl()).stream()
            .filter(block -> graphs.plain().containsKey(block.graph()))
            .toList();
        check(plainBlocks, "", allBlocks, allDivergences);

        // A stepped graph is read once per instant any of its files names. At each, the files
        // with a version at that instant are written and dated at it, the graph is captured over
        // every file it has met so far, and only the claims written at that instant are checked:
        // a file the reading did not write keeps its bytes, its rows keep the mark an earlier
        // reading gave them, and its blocks state that earlier reading's claims. The prelude is
        // the graph's own file, written at its first instant, so no file declares it twice.
        for (var graph : graphs.stepped().entrySet()) {
            var files = new LinkedHashSet<String>();
            files.add(PRELUDE_FILE);
            var standing = new LinkedHashMap<String, String>();
            boolean first = true;
            for (long instant : Graphs.instants(graph.getValue())) {
                var written = new LinkedHashMap<String, String>();
                if (first) {
                    written.put(PRELUDE_FILE, FactDocuments.prelude());
                    first = false;
                }
                graph.getValue().forEach((file, versions) -> {
                    var version = versions.get(instant);
                    if (version != null) {
                        written.put(file, version.sdl());
                        standing.put(file, version.sdl());
                        files.add(file);
                    }
                });
                var texts = List.copyOf(standing.values());
                var sourceNames = captured.andCatalogGraphReadAt(graph.getKey(), written, files,
                    Instant.ofEpochSecond(instant),
                    FactDocuments.StatedConfiguration.catalog(texts, jooq), corpus,
                    FactDocuments.StatedConfiguration.of(texts));
                var writtenNow = written.keySet().stream()
                    .filter(file -> !PRELUDE_FILE.equals(file))
                    .map(sourceNames::get)
                    .collect(Collectors.toSet());
                check(CorpusExpectations.blocksWrittenIn(captured.dsl(), writtenNow),
                    "@" + instant + " ", allBlocks, allDivergences);
            }
        }
        blocks = List.copyOf(allBlocks);
        divergences = List.copyOf(allDivergences);
    }

    /** How {@code current} disagrees with the store as it now stands, labelled with the reading. */
    private static void check(List<Block> current, String label, List<Block> allBlocks,
                              List<String> allDivergences) {
        allBlocks.addAll(current);
        CorpusExpectations.divergences(captured.dsl(), current).stream()
            .map(divergence -> label + divergence)
            .forEach(allDivergences::add);
    }

    @AfterAll
    static void closeTheStore() {
        if (captured != null) {
            captured.close();
        }
    }

    @Test
    @DisplayName("every declared row is a row the store holds")
    void everyDeclaredRowIsProduced() {
        assertThat(divergences)
            .as("a row a document declares and the relation does not hold, or the reverse where the "
                + "document claimed the whole population")
            .isEmpty();
    }

    /** A document the toolchain refuses fails here, unless it states the problem it means. */
    @Test
    @DisplayName("every document is one the toolchain accepts, or it states the problem")
    void everyDocumentTheToolchainRefusesSaysSo() {
        assertThat(CorpusExpectations.unstatedProblems(captured.dsl(), blocks))
            .as("problems capture recorded in a document that does not state graphql_schema_problem")
            .isEmpty();
    }

    /**
     * A scenario has no defect and a defect document has one, counted from what the store
     * records for the graph rather than from what the document states, so a document cannot pass
     * by forgetting to state what it holds. A scenario holding some names each one, so the report
     * says what to fix rather than how many things there are to find.
     */
    @Test
    @DisplayName("every scenario stores no defect, and every defect document stores one")
    void everyFolderHoldsWhatItClaims() {
        var defect = no.sikt.graphitron.model.Tables.GRAPHITRON_ENTRY_DEFECT;
        // The site only names what the position is about; the count is the defect relation's.
        var site = no.sikt.graphitron.model.Tables.GRAPHITRON_ENTRY_DEFECT_SITE;
        var problem = no.sikt.graphitron.model.Tables.GRAPHQL_SCHEMA_PROBLEM;
        var dsl = captured.dsl();
        var misplaced = new ArrayList<String>();
        claims.forEach((graph, claim) -> {
            if (claim != FactDocuments.Claim.SCENARIO && claim != FactDocuments.Claim.DEFECT) {
                return;
            }
            var held = new ArrayList<String>();
            dsl.select(defect.CODE, site.COORDINATE, defect.SOURCE_LINE, defect.SOURCE_COLUMN,
                    defect.DETAIL)
                .from(defect)
                .leftJoin(site).on(site.GRAPH_NAME.eq(defect.GRAPH_NAME),
                    site.SOURCE_NAME.eq(defect.SOURCE_NAME), site.SOURCE_LINE.eq(defect.SOURCE_LINE),
                    site.SOURCE_COLUMN.eq(defect.SOURCE_COLUMN), site.CODE.eq(defect.CODE))
                .where(defect.GRAPH_NAME.eq(graph))
                .orderBy(defect.SOURCE_LINE, defect.SOURCE_COLUMN, defect.CODE)
                .forEach(r -> held.add(r.value1() + " at " + r.value2() + " (" + r.value3() + ":"
                    + r.value4() + ")" + (r.value5() == null ? "" : ": " + r.value5())));
            dsl.select(problem.STAGE, problem.ERROR_CLASS, problem.MESSAGE, problem.SOURCE_LINE)
                .from(problem)
                .where(problem.GRAPH_NAME.eq(graph))
                .orderBy(problem.ORDINAL)
                .forEach(r -> held.add(r.value1() + " " + r.value2() + " (line " + r.value4()
                    + "): " + r.value3()));
            if ((claim == FactDocuments.Claim.SCENARIO) != held.isEmpty()) {
                misplaced.add(claim.folder().name() + "/" + graph + " stores " + held.size()
                    + " defects and schema problems" + (held.isEmpty() ? "" : ": " + held));
            }
        });
        assertThat(misplaced).as("documents whose folder claims what the store does not hold").isEmpty();
    }

    /** The undecided folder only shrinks: a document arrives in the folder whose claim it meets. */
    @Test
    @DisplayName("the undecided folder does not grow")
    void theUndecidedFolderDoesNotGrow() {
        assertThat(FactDocuments.documents(FactDocuments.Claim.UNDECIDED).size())
            .as("undecided documents; lower UNDECIDED_CEILING when one leaves")
            .isEqualTo(UNDECIDED_CEILING);
    }

    /**
     * Navigation is total and single-valued over every field the examples capture: each field
     * navigates as exactly one type, which is what lets a reader join the relation rather than
     * left-join it. Counted against the emitted fields rather than the written ones, a minted field
     * navigating like any other.
     */
    @Test
    @DisplayName("every field navigates as exactly one type")
    void everyFieldNavigatesAsExactlyOneType() {
        var f = no.sikt.graphitron.model.Tables.GRAPHITRON_FIELD;
        var nav = no.sikt.graphitron.model.Tables.INTENT_FIELD_NAVIGATED_TYPE;
        var dsl = captured.dsl();
        var count = dsl.selectCount().from(nav)
            .where(nav.GRAPH_NAME.eq(f.GRAPH_NAME), nav.TYPE_NAME.eq(f.TYPE_NAME),
                nav.FIELD_NAME.eq(f.FIELD_NAME))
            .<Integer>asField("navigations");
        var offenders = dsl.select(f.GRAPH_NAME, f.TYPE_NAME, f.FIELD_NAME, count)
            .from(f)
            .fetch()
            .stream()
            .filter(r -> r.value4() != 1)
            .map(r -> r.value1() + " " + r.value2() + "." + r.value3() + " navigates " + r.value4())
            .toList();
        assertThat(offenders).as("fields navigating as no type or as several").isEmpty();
        assertThat(dsl.fetchCount(f)).as("a store with no fields passes by having none").isPositive();
    }

    /**
     * The floor that makes an empty read loud. Every other assertion here passes over a folder that
     * has stopped resolving, a glob that has stopped matching, or a document whose blocks were all
     * deleted, so the one that cannot is worth stating separately.
     */
    @Test
    @DisplayName("the documents declare blocks at all")
    void theDocumentsDeclareBlocks() {
        assertThat(blocks)
            .as("a fact folder that declares nothing passes every sweep over it")
            .isNotEmpty();
        assertThat(blocks)
            .filteredOn(block -> block.mode() != CorpusExpectations.Mode.EMPTY)
            .allSatisfy(block -> assertThat(block.rows())
                .as("a block declaring no rows asserts nothing; @assertEmpty is how a document says "
                    + "a relation holds none")
                .isNotEmpty());
    }

    /**
     * Ragged lines are a cell count that disagrees with the header, which the decoder keeps rather
     * than guessing at. A document that lines its columns up by eye is easy to get wrong by one
     * comma, and the failure is otherwise a missing row rather than a malformed one.
     */
    @Test
    @DisplayName("no block has a line whose cells disagree with its header")
    void everyBlockIsWellFormed() {
        assertThat(blocks.stream().flatMap(block -> block.raggedLines().stream()).toList())
            .as("lines whose cell count differs from the header's")
            .isEmpty();
    }
}
