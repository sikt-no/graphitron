package no.sikt.graphitron.rewrite.classifieddsl;

import no.sikt.graphitron.model.catalog.StoreCatalog;
import no.sikt.graphitron.rewrite.CapturedStore;
import no.sikt.graphitron.rewrite.JooqCatalog;
import no.sikt.graphitron.rewrite.classifieddsl.CorpusExpectations.Block;
import no.sikt.graphitron.rewrite.test.tier.PipelineTier;
import org.jooq.DSLContext;
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
 *   <li><b>The assertable population is positive.</b> Blocks range over {@code intent_} and
 *       {@code graphitron_} relations only, never over the {@code graphql_*_directive} families the
 *       assertion mechanism itself is transcribed into.</li>
 *   <li><b>Values are checked as far as the store closes the set.</b> For a base-table column with a
 *       {@code CHECK (x IN (...))} clause, membership is checked against the DDL. A view column has no
 *       CHECK, and the vocabulary lives in the reading side's decode, so what recovers the diagnosis
 *       there is the failure message: a declared value no row of that relation carries anywhere reads
 *       as a typo rather than as a disagreement about behaviour.</li>
 * </ul>
 */
@PipelineTier
class CorpusExpectationTest {

    /** The prefixes a block may name. The assertion vocabulary stays outside the population it measures. */
    private static final List<String> ASSERTABLE_PREFIXES = List.of("intent_", "graphitron_");

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
        blocks = CorpusExpectations.blocks(dsl);
        catalog = StoreCatalog.read(dsl);
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
            .as("a block asserts an intent_ or graphitron_ relation: the corpus does not assert over "
                + "the graphql_*_directive families, which is where the assertion mechanism itself is "
                + "transcribed, so the vocabulary stays outside the population it measures")
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
