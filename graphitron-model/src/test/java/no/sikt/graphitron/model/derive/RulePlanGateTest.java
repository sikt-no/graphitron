package no.sikt.graphitron.model.derive;

import no.sikt.graphitron.model.derive.RulePlans.PartitionRead;
import no.sikt.graphitron.model.test.CapturedStore;
import no.sikt.graphitron.model.test.CatalogInput;
import no.sikt.graphitron.model.test.ClasspathCorpus;
import no.sikt.graphitron.model.test.ScaledSchemaFixture;
import no.sikt.graphitron.model.test.TestRunContext;
import org.jooq.DSLContext;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every rule a stage stores looks a row up through an index, so its cost follows its answer rather
 * than its input.
 *
 * <p>Each capture-time regression on sis has been one lookup reading a whole partition per row of
 * what drives it, which a small fixture hides and a large schema turns into minutes. The plan says
 * so before any row exists: {@link RulePlans} reads every rule's plan as its stage reads it and
 * names those lookups. A partition read on a table that holds a handful of rows per graph costs
 * nothing and is exempt by name; one known when this gate landed is listed with where it sits, and
 * the list only shrinks.
 *
 * <p>Over a capture of {@link ScaledSchemaFixture} rather than a hand-seeded store, because H2 plans
 * from the row counts it sees and a store of a dozen rows plans differently from sis; at this size
 * its plans match a sis capture's.
 */
class RulePlanGateTest {

    @TempDir
    Path tmp;

    /** The capture the two read-only cases share; the case that drops indexes captures its own. */
    private static CapturedStore shared;

    @BeforeAll
    static void capture(@TempDir Path directory) {
        shared = scaled(directory);
    }

    @AfterAll
    static void release() {
        shared.close();
    }

    /** Tables a partition read is free on, each with why. */
    private static final Map<String, String> SMALL = Map.of(
        "STORE_GRAPH_SOURCE", "one row per source a graph claims, tens on sis",
        "GRAPHQL_ROOT_OPERATION", "at most one row per root operation, three a graph",
        "FEDERATION_WRITTEN_VERSION", "one row per federation library",
        "GRAPHITRON_FEDERATION_LINK_IMPORT", "the names one link imports, a handful",
        "GRAPHITRON_OUTBOX_ENTRY", "one row per @outbox application, a few on a mutation field",
        "STORE_GRAPH_OUTBOX_PAYLOAD_ENTRY", "one row per entry of the configured common payload, a handful");

    /** Partition reads on trunk when this gate landed, each to be indexed or exempted. */
    private static final Set<String> KNOWN = Set.of(
        "GRAPHITRON_ENTRY_DEFECT_RULE SQL_TABLE",
        "GRAPHITRON_ENTRY_DEFECT_RULE CODE_CLASS",
        "GRAPHITRON_ENTRY_DEFECT_RULE GRAPHITRON_NODE",
        "GRAPHITRON_ENTRY_DEFECT_RULE GRAPHITRON_TYPE",
        "GRAPHITRON_PATH_ELEMENT_TABLE_RULE SQL_TABLE",
        "GRAPHITRON_FEDERATION_LINK_IMPORT_RULE GRAPHQL_AST_APPLIED_ARGUMENT_ENTRY",
        "GRAPHITRON_FEDERATION_LINK_IMPORT_RULE GRAPHQL_AST_VALUE_ENTRY",
        "GRAPHITRON_FEDERATION_LINK_IMPORT_RULE GRAPHITRON_DIRECTIVE_APPLICATION");

    @Test
    @DisplayName("no rule reads a partition per row outside the small tables and the known list")
    void everyRuleProbesAnIndexWhereItLooksARowUp() {
        assertThat(unexplained(shared.dsl()))
            .as("a lookup reading a whole partition per row: index the columns it looks up by")
            .isEmpty();
    }

    @Test
    @DisplayName("every known read still reads a partition, so the list only shrinks")
    void theKnownListHoldsNothingFixed() {
        assertThat(KNOWN)
            .as("a known read that no longer reads a partition: take it off the list")
            .isSubsetOf(keys(reads(shared.dsl())));
    }

    /**
     * The index each of two sis regressions was fixed by, dropped: the gate names the lookup it
     * served. The third, the federation imports, was a join order the plan does not show.
     */
    @Test
    @DisplayName("a lookup index dropped is a partition read the gate names")
    void aMissingLookupIndexFails() {
        withScaledStore(dsl -> {
            dsl.execute("DROP INDEX graphql_ast_element_entry_coordinate_ix");
            assertThat(keys(unexplainedReads(dsl)))
                .as("a field's declaration looked up by coordinate")
                .contains("GRAPHITRON_FIELD_TARGET_COLUMN_RULE GRAPHQL_AST_ELEMENT_ENTRY");
            dsl.execute("DROP INDEX sql_constraint_name_upper_ix");
            dsl.execute("DROP INDEX sql_constraint_jooq_name_upper_ix");
            assertThat(keys(unexplainedReads(dsl)))
                .as("a written key met by its folded name")
                .contains("GRAPHITRON_PATH_ELEMENT_KEY_RULE SQL_CONSTRAINT");
        });
    }

    private static List<String> unexplained(DSLContext dsl) {
        return unexplainedReads(dsl).stream().map(RulePlanGateTest::describe).toList();
    }

    private static List<PartitionRead> unexplainedReads(DSLContext dsl) {
        return reads(dsl).stream()
            .filter(read -> !SMALL.containsKey(read.table()))
            .filter(read -> !KNOWN.contains(key(read)))
            .toList();
    }

    private static List<PartitionRead> reads(DSLContext dsl) {
        var keyWidths = RulePlans.keyWidths(dsl);
        var rules = RulePlans.rules(dsl);
        assertThat(rules).as("the rule views").isNotEmpty();
        return rules.stream()
            .flatMap(rule -> RulePlans.partitionReads(rule,
                RulePlans.plan(dsl, rule, CapturedStore.GRAPH), keyWidths).stream())
            .toList();
    }

    private static Set<String> keys(List<PartitionRead> reads) {
        return reads.stream().map(RulePlanGateTest::key).collect(Collectors.toSet());
    }

    private static String key(PartitionRead read) {
        return read.rule() + " " + read.table();
    }

    private static String describe(PartitionRead read) {
        return read.rule() + " reads " + read.table() + ", " + read.how();
    }

    private void withScaledStore(Consumer<DSLContext> body) {
        try (var store = scaled(tmp)) {
            body.accept(store.dsl());
        }
    }

    private static CapturedStore scaled(Path directory) {
        return CapturedStore.ownStoreOfCatalogWith(directory, CapturedStore.GRAPH,
            ScaledSchemaFixture.scaledSdl(20), CatalogInput.of(TestRunContext.of()),
            ClasspathCorpus.entries());
    }
}
