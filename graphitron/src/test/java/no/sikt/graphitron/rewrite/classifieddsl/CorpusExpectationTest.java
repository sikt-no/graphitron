package no.sikt.graphitron.rewrite.classifieddsl;

import no.sikt.graphitron.model.catalog.StoreCatalog;
import no.sikt.graphitron.rewrite.CapturedStore;
import no.sikt.graphitron.model.jooq.JooqCatalog;
import no.sikt.graphitron.rewrite.classifieddsl.CorpusExpectations.Block;
import no.sikt.graphitron.rewrite.test.tier.PipelineTier;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static no.sikt.graphitron.common.configuration.TestConfiguration.testContext;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The corpus's fact expectations, asserted against a real capture of the whole corpus: every
 * {@code @expectEquals} block equals its relation's rows in its own document's graph.
 *
 * <p>This is the successor to a coordinate directive declaring the transitional walk's dimensional
 * tuple. The directive named arms of sealed Java types, so it could only assert what the walk
 * produced and only where a coordinate could carry it; a block names a relation and its columns, so it
 * asserts a store fact, and a relation keyed on something other than a coordinate is reachable on the
 * same mechanism. What the assertion is worth rests on the capture being real: rows reach this store
 * only through the capture pipeline, so a document cannot state a shape capture never produces.
 *
 * <p>One population is deliberately not captured, and says so in its name. The launcher command
 * relation is plan-tier: a planner derives it and no store relation for it is scheduled to arrive, so
 * to assert it at all this class produces it and lands it ({@link #landTheLauncherCommands}) before
 * the expectation pass. That buys the same declared-equals-produced strength per document that a
 * coordinate directive used to carry, and it costs the capture argument above, which is why these
 * rows live under their own {@code plan_} prefix and their own admitted case rather than merged into
 * the captured population.
 *
 * <p>All 57 documents are captured into one store, one graph per document, which is the shape
 * {@code derive/ColumnMatchShadowTest} already uses; the whole corpus's expectations are then one
 * query per relation and column list.
 *
 * <p>Beside the comparison sit the floors that keep a block honest:
 * <ul>
 *   <li><b>Names resolve.</b> The relation and every header cell resolve through {@link StoreCatalog},
 *       the booted store's own catalog reader, so a misspelling fails loudly rather than comparing
 *       nothing.</li>
 *   <li><b>Blocks are well formed.</b> No ragged row, no duplicate header cell, no {@code graph_name}
 *       column, and an empty block only where the relation's own comment says what its silence
 *       means.</li>
 *   <li><b>The assertable population is positive.</b> Blocks range over the captured
 *       {@code intent_} and {@code graphitron_} relations and the {@code plan_} apparatus, never
 *       over the {@code graphql_*_directive} families the assertion mechanism itself is transcribed
 *       into, and the captured and apparatus buckets stay tellable apart by name.</li>
 *   <li><b>Values are checked as far as the store closes the set.</b> For a base-table column with a
 *       {@code CHECK (x IN (...))} clause, membership is checked against the DDL. A view column has no
 *       CHECK, and the vocabulary lives in the reading side's decode, so what recovers the diagnosis
 *       there is the failure message: a declared value no row of that relation carries anywhere reads
 *       as a typo rather than as a disagreement about behaviour.</li>
 * </ul>
 */
@PipelineTier
class CorpusExpectationTest {

    /**
     * The captured population: relations whose rows reached the store through the capture
     * pipeline. A block over one of these is worth what it is worth because a document cannot
     * state a shape capture never produces.
     */
    private static final List<String> CAPTURED_PREFIXES = List.of("intent_", "graphitron_");

    /**
     * The apparatus population, admitted as its own case rather than folded into the captured
     * one. Its rows are a planner's output, landed by {@link #landTheLauncherCommands} a few
     * lines before the expectation pass reads them, so the "only capture writes here" argument
     * does not cover them and must not be allowed to look as though it does. Keeping the two
     * buckets apart is what lets a reader of any block tell "this asserts a captured fact" from
     * "this asserts a planner's output" without leaving the block.
     */
    private static final List<String> APPARATUS_PREFIXES = List.of(CorpusExpectations.APPARATUS_PREFIX);

    /** The prefixes a block may name. The assertion vocabulary stays outside the population it measures. */
    private static final List<String> ASSERTABLE_PREFIXES =
        Stream.concat(CAPTURED_PREFIXES.stream(), APPARATUS_PREFIXES.stream()).toList();

    /**
     * A relation whose comment says what its silence means may be asserted empty. Detected in the
     * comment rather than kept as a list here, because a second roster of relations would drift from
     * the relations themselves; the phrases are the ones the fact model uses to state absence.
     */
    static final List<String> SILENCE_IS_STATED =
        List.of("no row", "absent", "silence", "never a decline", "nothing");

    /** {@code CHECK (COL IN ('A', 'B'))}, the only closed-set shape the store spells in DDL. */
    static final Pattern CHECKED_MEMBERSHIP = Pattern.compile(
        "\"?(\\w+)\"?\\s+IN\\s*\\(([^)]*)\\)", Pattern.CASE_INSENSITIVE);

    @TempDir
    static Path tmp;

    private static CapturedStore captured;
    private static DSLContext dsl;
    private static List<Block> blocks;
    private static StoreCatalog catalog;

    @BeforeAll
    static void captureTheWholeCorpus() {
        var ctx = testContext();
        var jooq = new JooqCatalog(ctx.jooqPackage(), ctx.codegenLoader());
        var documents = CorpusDocuments.documents();
        captured = CapturedStore.ofCatalog(tmp, documents.getFirst().id(), full(documents.getFirst()), jooq);
        for (var document : documents.subList(1, documents.size())) {
            captured.andCatalogGraph(document.id(), full(document), jooq);
        }
        dsl = captured.dsl();
        landTheLauncherCommands(dsl);
        blocks = CorpusExpectations.blocks(dsl);
        catalog = StoreCatalog.read(dsl);
    }

    /**
     * Lands each document's produced launcher command rows so a block can assert them.
     *
     * <p>A {@code LOCAL TEMPORARY} table rather than a declared relation, and the choice is the
     * fact model's rather than a shortcut. These rows are a function of the schema and a
     * planner, not of captured facts, so as a declared relation they would be scaffolding and
     * owe the scaffolding charter: a roster row naming the writer, the cadence, the single
     * reader and the clock it drains on. There is no such clock. The launcher command relation
     * is plan-tier by design and no store relation for it is scheduled to arrive, so the charter
     * would have to state a removal criterion that can never fire, and the store's reference
     * pages would carry an entry for what is one test's apparatus. One reader, one connection,
     * one lifetime is exactly the case the per-reader shape is for.
     *
     * <p>{@code LOCAL} is load-bearing and not decoration: a bare {@code CREATE TEMPORARY TABLE}
     * defaults to {@code GLOBAL}, and H2's global temporary tables share their rows across every
     * attached session, so this reader's apparatus would become every reader's.
     *
     * <p>It lands in {@code PUBLIC} because that is where the store's own catalog reader looks;
     * a table anywhere else would not resolve and every block naming it would fail the
     * name-resolution floor instead of being compared. The cost is that this session's relation
     * census counts a relation no family covers, which is true only inside this class's own
     * connection and for as long as it is open.
     */
    private static void landTheLauncherCommands(DSLContext dsl) {
        dsl.execute("""
            CREATE LOCAL TEMPORARY TABLE %s (
              graph_name VARCHAR NOT NULL,
              type_name  VARCHAR NOT NULL,
              field_name VARCHAR NOT NULL,
              source     VARCHAR NOT NULL,
              result     VARCHAR NOT NULL,
              PRIMARY KEY (graph_name, type_name, field_name))
            """.formatted(CorpusExpectations.LAUNCHER_COMMAND_RELATION));

        var productions = ClassifiedHarness.launcherProductions();
        for (var document : CorpusDocuments.documents()) {
            if (!(productions.get(document.id())
                    instanceof ClassifiedHarness.LauncherProduction.Produced produced)) {
                continue;
            }
            for (var row : produced.relation().rows()) {
                dsl.insertInto(DSL.table(DSL.name(
                        CorpusExpectations.LAUNCHER_COMMAND_RELATION.toUpperCase(Locale.ROOT))))
                    .values(document.id(), row.coordinate().getTypeName(),
                        row.coordinate().getFieldName(),
                        row.source().getClass().getSimpleName(),
                        row.result().getClass().getSimpleName())
                    .execute();
            }
        }
    }

    @AfterAll
    static void closeTheStore() {
        if (captured != null) {
            captured.close();
        }
    }

    private static String full(CorpusDocuments.Document document) {
        return CorpusDocuments.prelude() + "\n" + document.sdl();
    }

    @Test
    void everyDeclaredRelationHoldsExactlyTheDeclaredRows() {
        var divergences = CorpusExpectations.divergences(dsl, blocks);
        assertThat(divergences)
            .as("every @expectEquals block equals its relation's rows in its own document's graph, "
                + "over the named columns. NOT_PRODUCED rows are declared and absent, NOT_DECLARED "
                + "rows are present and undeclared; paste a NOT_DECLARED row into the document's "
                + "block to declare it.%s", missingValueDiagnosis(divergences))
            .isEmpty();
    }

    @Test
    void everyRelationAndColumnNameResolves() {
        var columnsByRelation = new LinkedHashMap<String, Set<String>>();
        for (var relation : catalog.relations()) {
            columnsByRelation.put(relation.relationName().toLowerCase(Locale.ROOT),
                relation.columns().stream().map(c -> c.columnName().toLowerCase(Locale.ROOT))
                    .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new)));
        }
        var unresolved = blocks.stream()
            .flatMap(block -> CorpusExpectations.unresolvedNames(block, columnsByRelation).stream())
            .toList();
        assertThat(unresolved)
            .as("every relation and header cell a block names resolves through the store's own "
                + "catalog, so a misspelling fails here rather than comparing nothing")
            .isEmpty();
    }

    @Test
    void everyBlockIsWellFormed() {
        var commentsByRelation = new LinkedHashMap<String, String>();
        catalog.relations().forEach(relation ->
            commentsByRelation.put(relation.relationName().toLowerCase(Locale.ROOT), relation.comment()));

        var defects = blocks.stream()
            .flatMap(block -> CorpusExpectations.defects(block,
                ownsItsSilence(commentsByRelation
                    .getOrDefault(block.relation().toLowerCase(Locale.ROOT), ""))).stream())
            .toList();
        assertThat(defects).as("every block is well formed").isEmpty();
    }

    @Test
    void expectationsRangeOverTheAssertablePopulationOnly() {
        var outside = CorpusExpectations.relations(blocks).stream()
            .filter(relation -> ASSERTABLE_PREFIXES.stream()
                .noneMatch(prefix -> relation.toLowerCase(Locale.ROOT).startsWith(prefix)))
            .toList();
        assertThat(outside)
            .as("a block asserts an intent_ or graphitron_ captured relation, or a plan_ apparatus "
                + "relation this run landed: the corpus does not assert over the graphql_*_directive "
                + "families, which is where the assertion mechanism itself is transcribed, so the "
                + "vocabulary stays outside the population it measures")
            .isEmpty();
    }

    @Test
    void theApparatusPopulationStaysDistinguishable() {
        var confused = CorpusExpectations.relations(blocks).stream()
            .filter(relation -> {
                String name = relation.toLowerCase(Locale.ROOT);
                return CAPTURED_PREFIXES.stream().anyMatch(name::startsWith)
                    && APPARATUS_PREFIXES.stream().anyMatch(name::startsWith);
            })
            .toList();
        assertThat(confused)
            .as("no relation name reads as both a captured fact and a planner's output. The two "
                + "populations answer different questions: a captured row is one a document cannot "
                + "state without capture producing it, an apparatus row is one this run landed from "
                + "a planner, and a reader who cannot tell them apart cannot tell what a block claims")
            .isEmpty();
    }

    @Test
    void declaredValuesSitInsideACheckedVocabulary() {
        var violations = blocks.stream()
            .flatMap(block -> CorpusExpectations
                .membershipViolations(block, checkedVocabularies(block.relation())).stream())
            .toList();
        assertThat(violations)
            .as("where the store closes a column's set in DDL, a declared value outside it fails as a "
                + "membership error rather than as a row mismatch")
            .isEmpty();
    }

    /** Whether a relation's own comment says what its silence means, which is what an empty block needs. */
    static boolean ownsItsSilence(String comment) {
        String text = comment == null ? "" : comment.toLowerCase(Locale.ROOT);
        return SILENCE_IS_STATED.stream().anyMatch(text::contains);
    }

    /** The {@code CHECK (x IN (...))} vocabularies of one relation, by lower-cased column name. */
    static Map<String, Set<String>> checkedVocabularies(String relationName) {
        var vocabularies = new LinkedHashMap<String, Set<String>>();
        catalog.relations().stream()
            .filter(relation -> relation.relationName().equalsIgnoreCase(relationName))
            .flatMap(relation -> relation.checks().stream())
            .forEach(check -> {
                Matcher matcher = CHECKED_MEMBERSHIP.matcher(check.clause());
                while (matcher.find()) {
                    var literals = new LinkedHashSet<String>();
                    for (String literal : matcher.group(2).split(",")) {
                        String stripped = literal.strip();
                        if (stripped.startsWith("'") && stripped.endsWith("'") && stripped.length() > 1) {
                            literals.add(stripped.substring(1, stripped.length() - 1));
                        }
                    }
                    if (!literals.isEmpty()) {
                        vocabularies.merge(matcher.group(1).toLowerCase(Locale.ROOT), literals,
                            (first, second) -> {
                                var both = new LinkedHashSet<>(first);
                                both.retainAll(second);
                                return both;
                            });
                    }
                }
            });
        return vocabularies;
    }

    /**
     * The weaker signal for a view column, whose vocabulary the store does not close: a declared value
     * no row of that relation carries in any document reads as a typo, and this line is what says so.
     */
    private static String missingValueDiagnosis(List<CorpusExpectations.Divergence> divergences) {
        var lines = new ArrayList<String>();
        for (var divergence : divergences) {
            if (divergence.side() != CorpusExpectations.Divergence.Side.NOT_PRODUCED) {
                continue;
            }
            var columns = blocks.stream()
                .filter(block -> block.relation().equals(divergence.relation()))
                .map(Block::columns)
                .findFirst()
                .orElse(List.of());
            for (int i = 0; i < columns.size() && i < divergence.values().size(); i++) {
                String value = divergence.values().get(i);
                if (value == null || holdsValueAnywhere(divergence.relation(), columns.get(i), value)) {
                    continue;
                }
                lines.add("%n  %s.%s = '%s': no row in any document carries this value"
                    .formatted(divergence.relation(), columns.get(i), value));
            }
        }
        return lines.isEmpty() ? "" : String.join("", new LinkedHashSet<>(lines));
    }

    private static boolean holdsValueAnywhere(String relation, String column, String value) {
        return dsl.fetchExists(dsl.selectOne()
            .from(org.jooq.impl.DSL.table(org.jooq.impl.DSL.name(relation.toUpperCase(Locale.ROOT))))
            .where(org.jooq.impl.DSL.field(org.jooq.impl.DSL.name(column.toUpperCase(Locale.ROOT)))
                .cast(String.class).eq(value)));
    }
}
