package no.sikt.graphitron.model.test;

import no.sikt.graphitron.model.test.CorpusExpectations.Block;
import org.jooq.DSLContext;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
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

import static no.sikt.graphitron.model.Tables.GRAPHITRON_FIELD;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The fact examples: what each document under {@code src/test/resources/facts} says the store holds
 * for it.
 *
 * <p>A document's folder is a claim about its graph, checked against the store
 * ({@link FactDocuments.Claim}).
 * {@code scenarios} generate, and the {@code graphitron} module generates each one at
 * {@code generate-test-sources} for the tests of generated code, so a scenario never holds a
 * defect; {@code defects} never generate and always store one; {@code undecided} makes no claim
 * and only shrinks. A folder's claim is judged on a scenario's final reading. Every document is a
 * {@link Scenario}, run by the population: its claims are checked as it runs, each against the
 * reading before it, and this class asserts what they found.
 *
 * <p>The corpus beside it carries classification verdicts for the walk and asserts nothing about
 * the store; a document here is about the shape of the store, and is where a store fact is pinned.
 *
 * <p>What this class does not carry is the corpus's other obligations, and the absence is the point:
 * no projection operation, no documentation fragment, no verdict, no launcher-command apparatus. A
 * fact document is an SDL example and the rows it says the store holds for it.
 *
 * <p>A scenario can read its graph in steps: {@code @tick} moves its clock and a later
 * {@code @withFacts} writes the next version of a file, so a claim about a second reading is written
 * after the step that produces it, and a mark column says which reading last wrote a row, which is
 * what tells a row the sweep kept from one a later reading wrote again. {@code @withConfiguration}
 * is a step too: each capture runs under the last one stated above it.
 */
class FactExpectationTest {

    /** The undecided folder's size, lowered as documents leave it and never raised. */
    private static final int UNDECIDED_CEILING = 40;

    /** Each scenario's claim, by its graph's name, which is its path. */
    private static final Map<String, FactDocuments.Claim> claims = new LinkedHashMap<>();

    @RegisterExtension
    static final FactGraphs.Reader FACTS = FactGraphs.reader();

    private static final List<Block> blocks = new ArrayList<>();
    private static final List<String> divergences = new ArrayList<>();
    private static final List<String> unstated = new ArrayList<>();
    private static final List<String> misplaced = new ArrayList<>();
    private static final List<String> unnavigated = new ArrayList<>();
    private static final List<String> wholeStore = new ArrayList<>();
    private static final List<String> refusedAssertions = new ArrayList<>();

    /**
     * How many pins documents make of a behaviour some other document already pins, outside its
     * keystone: lowered as duplicates merge and never raised.
     */
    private static final int DUPLICATE_PINS = 127;

    /** What each document's claims pin, by its path. */
    private static final Map<String, Map<String, java.util.Set<Pins.Pin>>> pinned = new TreeMap<>();

    /** Each plain graph's facts, as {@link #SHAPE} projects them. */
    private static final Map<String, java.util.Set<String>> fingerprints = new TreeMap<>();
    private static int fields;


    @BeforeAll
    static void captureEveryDocument() {
        var ctx = TestRunContext.of();
        var jooq = CatalogInput.of(ctx);
        var claimed = FactDocuments.claimed();
        // -Dfacts=<prefix>[,<prefix>...] reads only the documents whose path starts with one, as
        // -Dfacts=defects/entry-defect or -Dfacts=scenarios/, for iterating on a document without
        // capturing the rest. A build sets nothing and reads them all.
        var only = System.getProperty("facts");
        if (only != null) {
            var prefixes = List.of(only.split(","));
            claimed.entrySet().removeIf(entry -> prefixes.stream()
                .noneMatch(FactDocuments.path(entry.getValue(), entry.getKey())::startsWith));
        }
        claimed.forEach((document, claim) -> claims.put(FactDocuments.path(claim, document), claim));
        var scenarios = new LinkedHashMap<String, CorpusDocuments.Document>();
        claimed.forEach((document, claim) -> scenarios.put(FactDocuments.path(claim, document), document));

        // The plain graphs are the population's, captured once for the run whichever class asks
        // first, each store's on a thread of its own, and checked through the select-only reader:
        // a check that wrote would be refused, which is the population's own promise.
        //
        // Beside them, this module's assertion files: claims about a document stated without
        // editing it, checked against the document's graph like the document's own.
        var assertionBlocks = new ArrayList<Block>();
        var documentPaths = plainDocuments().keySet();
        for (var file : FactAssertions.in(FactAssertions.FOLDER)) {
            var refusals = file.refusals(documentPaths);
            refusedAssertions.addAll(refusals);
            if (refusals.isEmpty() && scenarios.containsKey(file.document())) {
                assertionBlocks.addAll(file.blocks());
            }
        }
        FACTS.warm(scenarios.keySet());
        // Each scenario's claims were checked as it ran, against the store at the claim's point;
        // what they found is the record the population keeps.
        var verified = new LinkedHashMap<String, FactGraphs.Verification>();
        FACTS.verifications().forEach((graph, verification) -> {
            if (scenarios.containsKey(graph)) {
                verified.put(graph, verification);
                divergences.addAll(verification.divergences().stream()
                    .map(divergence -> graph + " " + divergence).toList());
                Pins.merge(pinned.computeIfAbsent(graph, ignored -> new TreeMap<>()), verification.pins());
            }
        });
        for (var examined : FACTS.eachStore((dsl, held) -> {
            var files = assertionBlocks.stream().filter(block -> held.contains(block.graph())).toList();
            var own = java.util.stream.Stream.concat(
                    verified.entrySet().stream()
                        .filter(entry -> held.contains(entry.getKey()))
                        .flatMap(entry -> entry.getValue().blocks().stream()),
                    files.stream())
                .toList();
            return examine(dsl, held, own, CorpusExpectations.divergences(dsl, files).stream()
                .map(Object::toString).toList());
        })) {
            examined.addTo();
        }
        FACTS.eachStore((dsl, held) -> fingerprints(dsl, held, scenarios.keySet()))
            .forEach(fingerprints::putAll);
        // An assertion file pins for the document it names, on the final reading it is checked against.
        FACTS.eachStore((dsl, held) -> {
            var byDocument = new TreeMap<String, Map<String, java.util.Set<Pins.Pin>>>();
            assertionBlocks.stream().filter(block -> held.contains(block.graph()))
                .collect(java.util.stream.Collectors.groupingBy(Block::graph))
                .forEach((graph, files) -> byDocument.put(graph, Pins.of(dsl, files)));
            return byDocument;
        }).forEach(byDocument -> byDocument.forEach((graph, pins) ->
            Pins.merge(pinned.computeIfAbsent(graph, ignored -> new TreeMap<>()), pins)));
    }

    /** What one store says about the blocks its graphs declare, gathered for the cases below. */
    private record Examined(List<Block> blocks, List<String> divergences, List<String> unstated,
                            List<String> misplaced, List<String> unnavigated, int fields,
                            List<String> wholeStore) {

        void addTo() {
            FactExpectationTest.blocks.addAll(blocks);
            FactExpectationTest.divergences.addAll(divergences);
            FactExpectationTest.unstated.addAll(unstated);
            FactExpectationTest.misplaced.addAll(misplaced);
            FactExpectationTest.unnavigated.addAll(unnavigated);
            FactExpectationTest.fields += fields;
            FactExpectationTest.wholeStore.addAll(wholeStore);
        }
    }

    /**
     * Every check over one store: {@code held} the graphs it holds, {@code own} the blocks they
     * declare, and {@code divergences} how those blocks disagree with it.
     */
    private static Examined examine(DSLContext dsl, java.util.Set<String> held, List<Block> own,
                                    List<String> divergences) {
        return new Examined(own, divergences, CorpusExpectations.unstatedProblems(dsl, own),
            misplaced(dsl, held), unnavigated(dsl), dsl.fetchCount(GRAPHITRON_FIELD),
            wholeStore(dsl, own));
    }

    /**
     * A scenario has no defect and a defect document has one, counted from what the store
     * records for the graph rather than from what the document states, so a document cannot pass
     * by forgetting to state what it holds. A scenario holding some names each one, so the report
     * says what to fix rather than how many things there are to find.
     */
    private static List<String> misplaced(DSLContext dsl, java.util.Set<String> held) {
        var defect = no.sikt.graphitron.model.Tables.GRAPHITRON_ENTRY_DEFECT;
        // The site only names what the position is about; the count is the defect relation's.
        var site = no.sikt.graphitron.model.Tables.GRAPHITRON_ENTRY_DEFECT_SITE;
        var problem = no.sikt.graphitron.model.Tables.GRAPHQL_SCHEMA_PROBLEM;
        var found = new ArrayList<String>();
        claims.forEach((graph, claim) -> {
            if (!held.contains(graph)
                    || claim != FactDocuments.Claim.SCENARIO && claim != FactDocuments.Claim.DEFECT) {
                return;
            }
            var stored = new ArrayList<String>();
            dsl.select(defect.CODE, site.COORDINATE, defect.SOURCE_LINE, defect.SOURCE_COLUMN,
                    defect.DETAIL)
                .from(defect)
                .leftJoin(site).on(site.GRAPH_NAME.eq(defect.GRAPH_NAME),
                    site.SOURCE_NAME.eq(defect.SOURCE_NAME), site.SOURCE_LINE.eq(defect.SOURCE_LINE),
                    site.SOURCE_COLUMN.eq(defect.SOURCE_COLUMN), site.CODE.eq(defect.CODE))
                .where(defect.GRAPH_NAME.eq(graph))
                .orderBy(defect.SOURCE_LINE, defect.SOURCE_COLUMN, defect.CODE)
                .forEach(r -> stored.add(r.value1() + " at " + r.value2() + " (" + r.value3() + ":"
                    + r.value4() + ")" + (r.value5() == null ? "" : ": " + r.value5())));
            dsl.select(problem.STAGE, problem.ERROR_CLASS, problem.MESSAGE, problem.SOURCE_LINE)
                .from(problem)
                .where(problem.GRAPH_NAME.eq(graph))
                .orderBy(problem.ORDINAL)
                .forEach(r -> stored.add(r.value1() + " " + r.value2() + " (line " + r.value4()
                    + "): " + r.value3()));
            if ((claim == FactDocuments.Claim.SCENARIO) != stored.isEmpty()) {
                found.add(graph + " stores " + stored.size() + " defects and schema problems"
                    + (stored.isEmpty() ? "" : ": " + stored));
            }
        });
        return found;
    }

    /**
     * Navigation is total and single-valued over every field the examples capture: each field
     * navigates as exactly one type, which is what lets a reader join the relation rather than
     * left-join it. Counted against the emitted fields rather than the written ones, a minted field
     * navigating like any other.
     */
    private static List<String> unnavigated(DSLContext dsl) {
        var f = GRAPHITRON_FIELD;
        var nav = no.sikt.graphitron.model.Tables.INTENT_FIELD_NAVIGATED_TYPE;
        var count = dsl.selectCount().from(nav)
            .where(nav.GRAPH_NAME.eq(f.GRAPH_NAME), nav.TYPE_NAME.eq(f.TYPE_NAME),
                nav.FIELD_NAME.eq(f.FIELD_NAME))
            .<Integer>asField("navigations");
        return dsl.select(f.GRAPH_NAME, f.TYPE_NAME, f.FIELD_NAME, count)
            .from(f)
            .fetch()
            .stream()
            .filter(r -> r.value4() != 1)
            .map(r -> r.value1() + " " + r.value2() + "." + r.value3() + " navigates " + r.value4())
            .toList();
    }

    /**
     * The blocks claiming a store-wide relation's whole population. A relation keyed by no graph
     * holds what every capture into its store wrote, so a document can add to or subtract from what
     * it says, never state all of it: no document owns the store.
     */
    private static List<String> wholeStore(DSLContext dsl, List<Block> own) {
        return own.stream()
            .filter(block -> block.mode() == CorpusExpectations.Mode.EQUALS
                || block.mode() == CorpusExpectations.Mode.EMPTY)
            .filter(block -> !CorpusExpectations.partitioned(dsl, block.relation()))
            .map(block -> block.graph() + " " + block.mode() + " " + block.relation())
            .toList();
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
        assertThat(unstated)
            .as("problems capture recorded in a document that does not state graphql_schema_problem")
            .isEmpty();
    }

    @Test
    @DisplayName("every scenario stores no defect, and every defect document stores one")
    void everyFolderHoldsWhatItClaims() {
        assertThat(misplaced).as("documents whose folder claims what the store does not hold").isEmpty();
    }

    /**
     * An assertion file names a document that exists and only adds to what the document states:
     * the whole population of a relation is the document's own statement.
     */
    @Test
    @DisplayName("every assertion file names a document and only adds to what it states")
    void everyAssertionFileNamesADocumentAndOnlyAdds() {
        assertThat(refusedAssertions).as("assertion files that cannot be checked as they stand").isEmpty();
    }

    @Test
    @DisplayName("no document claims a store-wide relation's whole population")
    void noDocumentClaimsTheWholeStore() {
        assertThat(wholeStore)
            .as("@expectEquals or @assertEmpty on a relation keyed by no graph; state it with "
                + "@assertContains or @assertAbsent")
            .isEmpty();
    }

    /**
     * Each example is stated once. Two documents whose graphs hold the same facts state one example
     * twice, and every claim on one is a claim the other could carry; they merge.
     *
     * <p>The comparison is the store's rather than the text's, so an example spelled two ways is
     * one example and two that read alike but differ in a directive are two. A document stating no
     * SDL of its own is exempt: its example is what it pulls in, the catalog or the configuration
     * read over the anchor, and its subject is all in its claims.
     */
    @Test
    @DisplayName("no two documents state the same example")
    void noTwoDocumentsStateTheSameExample() {
        var documents = plainDocuments();
        var byFacts = new LinkedHashMap<java.util.Set<String>, List<String>>();
        fingerprints.forEach((graph, facts) -> {
            if (Scenario.statesExample(documents.get(graph).sdl())) {
                byFacts.computeIfAbsent(facts, ignored -> new ArrayList<>()).add(graph);
            }
        });
        assertThat(byFacts.values().stream().filter(graphs -> graphs.size() > 1).toList())
            .as("documents whose graphs hold the same facts; merge them")
            .isEmpty();
    }

    /**
     * What an example is, as the model states it: one row per fact, its graph beside it. Declared
     * rather than everything the store holds, so a new relation changes no fingerprint until
     * somebody adds a select here. Types and their kind, fields and arguments at their type
     * expressions, the directives applied where (the author's, claim blocks aside, and the ones
     * expansion minted), the class standing for a type and the shape of result it holds, the defects
     * and schema problems the reading found, and the configuration the graph is captured under: two
     * documents stating one schema under two configurations are two examples.
     */
    private static final String SHAPE = """
        SELECT graph_name, CONCAT_WS('|', 'type', coordinate, kind) FROM graphitron_type
        UNION ALL SELECT graph_name, CONCAT_WS('|', 'field', coordinate, type_sdl) FROM graphitron_field
        UNION ALL SELECT graph_name, CONCAT_WS('|', 'argument', coordinate, type_sdl, default_value_sdl)
          FROM graphitron_argument
        UNION ALL SELECT graph_name, CONCAT_WS('|', 'applied', coordinate, directive_name)
          FROM graphql_directive_application
          WHERE directive_name NOT IN
            ('expectEquals', 'assertContains', 'assertAbsent', 'assertEmpty', 'withConfiguration')
        UNION ALL SELECT graph_name, CONCAT_WS('|', 'emitted', coordinate, directive_name)
          FROM graphitron_directive_application
        UNION ALL SELECT graph_name, CONCAT_WS('|', 'backing', type_name, class_name)
          FROM graphitron_type_backing
        UNION ALL SELECT graph_name, CONCAT_WS('|', 'result', type_name, shape) FROM graphitron_result
        UNION ALL SELECT graph_name, CONCAT_WS('|', 'defect', code, coordinate, detail)
          FROM graphitron_entry_defect_site
        UNION ALL SELECT graph_name, CONCAT_WS('|', 'problem', stage, error_class)
          FROM graphql_schema_problem
        UNION ALL SELECT graph_name, CONCAT_WS('|', 'page size', max_page_size)
          FROM store_graph_max_allowed_page_size
        UNION ALL SELECT graph_name, CONCAT_WS('|', 'outbox', table_name, function_name, payload_mapping)
          FROM store_graph_outbox
        UNION ALL SELECT graph_name, CONCAT_WS('|', 'outbox role', kind, role, name)
          FROM store_graph_outbox_role
        UNION ALL SELECT graph_name, CONCAT_WS('|', 'outbox payload', position, key_name, bound_to)
          FROM store_graph_outbox_payload_entry
        UNION ALL SELECT graph_name, CONCAT_WS('|', 'session', handle_class) FROM store_graph_session_handle
        UNION ALL SELECT graph_name, 'session supplied' FROM store_graph_session_supplied
        UNION ALL SELECT graph_name, CONCAT_WS('|', 'supergraph', supergraph_name)
          FROM store_graph_supergraph
        UNION ALL SELECT graph_name, CONCAT_WS('|', 'tenant', column_name) FROM store_graph_tenant_column
        UNION ALL SELECT graph_name, CONCAT_WS('|', 'lint skips', type_pattern)
          FROM store_graph_lint_excluded_type
        UNION ALL SELECT graph_name, CONCAT_WS('|', 'annotated', ordinal, tag, description_note)
          FROM store_graph_schema_input WHERE tag IS NOT NULL OR description_note IS NOT NULL
        """;

    /** The facts {@link #SHAPE} states for each of {@code plain}'s graphs that {@code dsl}'s store holds. */
    private static Map<String, java.util.Set<String>> fingerprints(DSLContext dsl, java.util.Set<String> held,
                                                                   java.util.Set<String> plain) {
        var facts = new TreeMap<String, java.util.Set<String>>();
        held.stream().filter(plain::contains)
            .forEach(graph -> facts.put(graph, new java.util.HashSet<>()));
        for (var row : dsl.resultQuery(SHAPE).fetch()) {
            var graph = facts.get(row.get(0, String.class));
            if (graph != null) {
                graph.add(row.get(1, String.class));
            }
        }
        return facts;
    }

    /** Every plain document, by path, whatever {@code -Dfacts} narrowed the capture to. */
    private static Map<String, CorpusDocuments.Document> plainDocuments() {
        var plain = new TreeMap<String, CorpusDocuments.Document>();
        FactDocuments.claimed().forEach((document, claim) ->
            plain.put(FactDocuments.path(claim, document), document));
        return plain;
    }

    /**
     * Each behaviour is pinned once. A document pins a behaviour when its claims assert rows of it;
     * a second document pinning the same shape is a second statement of one case, and merges into
     * the first or names the case it is. Counted rather than refused, since the corpus predates the
     * rule, and only over the whole corpus: a run narrowed by {@code -Dfacts} sees part of it.
     */
    @Test
    @DisplayName("no behaviour is pinned by more documents than it was")
    void duplicatePinsDoNotGrow() {
        org.junit.jupiter.api.Assumptions.assumeTrue(System.getProperty("facts") == null,
            "-Dfacts narrows the corpus the count is over");
        var flat = new TreeMap<String, java.util.Set<Pins.Pin>>();
        pinned.forEach((document, byRelation) -> flat.put(document, Pins.all(byRelation)));
        var duplicates = Pins.duplicates(flat);
        assertThat(Pins.surplus(duplicates))
            .as(() -> "pins beyond the first of each behaviour; lower DUPLICATE_PINS when documents merge:\n"
                + String.join("\n", Pins.report(duplicates)))
            .isEqualTo(DUPLICATE_PINS);
    }

    /**
     * Where each behaviour's keystone is, and where there is none: written to
     * {@code target/facts-report/keystones.txt} and gated on nothing, so documentation work goes
     * where examples pile up without a page. A keystone stands for what its document's claims on the
     * relation its page shows pin, and its document must be one the corpus captures.
     */
    @Test
    @DisplayName("the keystone report names every behaviour a page shows and every one it does not")
    void keystoneReport() throws java.io.IOException {
        org.junit.jupiter.api.Assumptions.assumeTrue(System.getProperty("facts") == null,
            "-Dfacts narrows the corpus the report is over");
        var docs = java.util.stream.Stream.of(java.nio.file.Path.of("..", "docs"), java.nio.file.Path.of("docs"))
            .filter(java.nio.file.Files::isDirectory).findFirst().orElseThrow();
        var keystones = Pins.keystones(docs);
        assertThat(keystones).as("examples showing rows").isNotEmpty();
        assertThat(keystones.stream().map(Pins.Keystone::document).filter(document -> !pinned.containsKey(document)))
            .as("keystones naming a document the corpus does not capture or that claims nothing")
            .isEmpty();
        var report = java.nio.file.Path.of("target", "facts-report", "keystones.txt");
        java.nio.file.Files.createDirectories(report.getParent());
        java.nio.file.Files.write(report, Pins.keystoneReport(pinned, keystones, claims.keySet()));
    }

    /**
     * Every building block is pulled in by something a scenario runs: a shape or layout nothing
     * reaches is setup no test reads, and goes.
     */
    @Test
    @DisplayName("every building block is pulled in, directly or through another")
    void everyBuildingBlockIsPulledIn() {
        var blocks = FactDocuments.buildingBlocks();
        var reached = new java.util.HashSet<String>();
        var pending = new java.util.ArrayDeque<String>();
        plainDocuments().values().forEach(document -> pending.addAll(Scenario.pulls(document.sdl())));
        while (!pending.isEmpty()) {
            var path = pending.pop();
            if (reached.add(path) && blocks.containsKey(path)) {
                pending.addAll(Scenario.pulls(blocks.get(path).sdl()));
            }
        }
        assertThat(blocks.keySet().stream().filter(path -> !reached.contains(path)).toList())
            .as("building blocks no scenario pulls in, directly or through another block")
            .isEmpty();
    }

    /**
     * A building block states no claim. It is never run on its own, so a claim in it would never be
     * checked: claims belong to the scenario that pulls the block in, after the step they are about.
     */
    @Test
    @DisplayName("no building block states a claim")
    void noBuildingBlockClaims() {
        assertThat(FactDocuments.buildingBlocks().entrySet().stream()
                .filter(entry -> Scenario.claims(entry.getValue().sdl()))
                .map(Map.Entry::getKey)
                .toList())
            .as("building blocks stating claims nothing checks; move them into the scenario")
            .isEmpty();
    }

    /** The undecided folder only shrinks: a document arrives in the folder whose claim it meets. */
    @Test
    @DisplayName("the undecided folder does not grow")
    void theUndecidedFolderDoesNotGrow() {
        assertThat(FactDocuments.documents(FactDocuments.Claim.UNDECIDED).size())
            .as("undecided documents; lower UNDECIDED_CEILING when one leaves")
            .isEqualTo(UNDECIDED_CEILING);
    }

    @Test
    @DisplayName("every field navigates as exactly one type")
    void everyFieldNavigatesAsExactlyOneType() {
        assertThat(unnavigated).as("fields navigating as no type or as several").isEmpty();
        assertThat(fields).as("a store with no fields passes by having none").isPositive();
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
