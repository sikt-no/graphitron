package no.sikt.graphitron.model;

import no.sikt.graphitron.model.capture.config.StoredRecipe;
import no.sikt.graphitron.model.capture.store.StoreEntries;
import no.sikt.graphitron.model.config.SessionStateConfig;
import no.sikt.graphitron.model.lint.LintConfig;
import no.sikt.graphitron.model.run.OutputCoordinates;
import no.sikt.graphitron.model.run.SubjectConfig;
import no.sikt.graphitron.model.schema.input.SchemaRecipe;
import no.sikt.graphitron.model.schema.input.SchemaSource;
import org.jooq.DSLContext;
import org.jooq.Table;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static no.sikt.graphitron.model.Tables.STORE_GRAPH_LINT_DISABLED_RULE;
import static no.sikt.graphitron.model.Tables.STORE_GRAPH_LINT_EXCLUDED_TYPE;
import static no.sikt.graphitron.model.Tables.STORE_GRAPH_OUTPUT;
import static no.sikt.graphitron.model.Tables.STORE_GRAPH_SCHEMA_EXTENSION;
import static no.sikt.graphitron.model.Tables.STORE_GRAPH_SCHEMA_INPUT;
import static no.sikt.graphitron.model.Tables.STORE_GRAPH_SESSION_MOUNT;
import static no.sikt.graphitron.model.Tables.STORE_GRAPH_SESSION_UNMOUNT;
import static no.sikt.graphitron.model.Tables.STORE_GRAPH_SUPERGRAPH;
import static no.sikt.graphitron.model.Tables.STORE_GRAPH_TENANT_COLUMN;
import static no.sikt.graphitron.model.test.SeededStore.seedGraph;
import static no.sikt.graphitron.model.test.SeededStore.withSeededStore;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

/**
 * The configuration transcription, on the two things a run cannot say for itself: what it was
 * configured with, and that it stopped being configured with something.
 *
 * <p>The first half is ordinary and every case below states one relation's worth of it. The second
 * is what the mark and sweep is for and is the half a writer gets wrong: an author deleting an
 * element from the build file produces no row to overwrite, so a store that only ever upserts keeps
 * answering with the configuration of a run that has exited.
 */
class StoreEntriesTest {

    private static final String GRAPH = "config";
    private static final String SIBLING = "sibling";
    private static final LocalDateTime FIRST = LocalDateTime.of(2026, 1, 1, 12, 0);
    private static final LocalDateTime SECOND = FIRST.plusMinutes(1);

    @Test
    @DisplayName("every configured parameter lands as the run held it")
    void transcribesTheWholeSubject() {
        withSeededStore(dsl -> {
            seedGraph(dsl, GRAPH);
            StoreEntries.write(dsl, GRAPH, everything(), FIRST);

            assertThat(dsl.select(STORE_GRAPH_SCHEMA_INPUT.ORDINAL, STORE_GRAPH_SCHEMA_INPUT.KIND,
                    STORE_GRAPH_SCHEMA_INPUT.ENTRY_VALUE, STORE_GRAPH_SCHEMA_INPUT.TAG,
                    STORE_GRAPH_SCHEMA_INPUT.DESCRIPTION_NOTE)
                .from(STORE_GRAPH_SCHEMA_INPUT)
                .where(STORE_GRAPH_SCHEMA_INPUT.GRAPH_NAME.eq(GRAPH))
                .orderBy(STORE_GRAPH_SCHEMA_INPUT.ORDINAL)
                .fetch(row -> tuple(row.value1(), row.value2(), row.value3(), row.value4(),
                    row.value5())))
                .as("the recipe keeps its own order, and each entry says which door it came in by")
                .containsExactly(
                    tuple(0, "pattern", "src/main/resources/**.graphqls", null, null),
                    tuple(1, "file", "/schema/extra.graphqls", "extra", "from the extra file"),
                    tuple(2, "named", "bundled", null, null));

            assertThat(dsl.select(STORE_GRAPH_SCHEMA_EXTENSION.EXTENSION)
                .from(STORE_GRAPH_SCHEMA_EXTENSION)
                .where(STORE_GRAPH_SCHEMA_EXTENSION.GRAPH_NAME.eq(GRAPH))
                .orderBy(STORE_GRAPH_SCHEMA_EXTENSION.ORDINAL)
                .fetch(0, String.class))
                .containsExactly(".graphqls", ".graphql");

            assertThat(dsl.select(STORE_GRAPH_SUPERGRAPH.SUPERGRAPH_NAME)
                .from(STORE_GRAPH_SUPERGRAPH)
                .where(STORE_GRAPH_SUPERGRAPH.GRAPH_NAME.eq(GRAPH))
                .fetchOne(0, String.class))
                .isEqualTo("catalogue");

            assertThat(dsl.select(STORE_GRAPH_OUTPUT.OUTPUT_PACKAGE, STORE_GRAPH_OUTPUT.JOOQ_PACKAGE,
                    STORE_GRAPH_OUTPUT.OUTPUT_DIRECTORY)
                .from(STORE_GRAPH_OUTPUT)
                .where(STORE_GRAPH_OUTPUT.GRAPH_NAME.eq(GRAPH))
                .fetch(row -> tuple(row.value1(), row.value2(), row.value3())))
                .as("the three coordinates travel together and are written together")
                .containsExactly(tuple("com.example.generated", "com.example.jooq",
                    Path.of("/build/generated").toString()));

            assertThat(dsl.select(STORE_GRAPH_TENANT_COLUMN.COLUMN_NAME)
                .from(STORE_GRAPH_TENANT_COLUMN)
                .where(STORE_GRAPH_TENANT_COLUMN.GRAPH_NAME.eq(GRAPH))
                .fetchOne(0, String.class))
                .isEqualTo("tenant_id");

            assertThat(dsl.select(STORE_GRAPH_LINT_DISABLED_RULE.RULE_ID)
                .from(STORE_GRAPH_LINT_DISABLED_RULE)
                .where(STORE_GRAPH_LINT_DISABLED_RULE.GRAPH_NAME.eq(GRAPH))
                .fetch(0, String.class))
                .as("a set, so the rows carry no position for a reader to mistake for one")
                .containsExactlyInAnyOrder("no-typename-prefix", "field-names-camel-case");

            assertThat(dsl.select(STORE_GRAPH_LINT_EXCLUDED_TYPE.TYPE_PATTERN)
                .from(STORE_GRAPH_LINT_EXCLUDED_TYPE)
                .where(STORE_GRAPH_LINT_EXCLUDED_TYPE.GRAPH_NAME.eq(GRAPH))
                .orderBy(STORE_GRAPH_LINT_EXCLUDED_TYPE.ORDINAL)
                .fetch(0, String.class))
                .as("a list, so the author's order is the row order")
                .containsExactly("Legacy*", "*Internal");

            assertThat(dsl.select(STORE_GRAPH_SESSION_MOUNT.MOUNT_METHOD)
                .from(STORE_GRAPH_SESSION_MOUNT)
                .where(STORE_GRAPH_SESSION_MOUNT.GRAPH_NAME.eq(GRAPH))
                .fetchOne(0, String.class))
                .isEqualTo("com.example.Identity#mount");

            assertThat(dsl.select(STORE_GRAPH_SESSION_UNMOUNT.UNMOUNT_METHOD)
                .from(STORE_GRAPH_SESSION_UNMOUNT)
                .where(STORE_GRAPH_SESSION_UNMOUNT.GRAPH_NAME.eq(GRAPH))
                .fetchOne(0, String.class))
                .isEqualTo("com.example.Identity#unmount");
        });
    }

    @Test
    @DisplayName("the recipe decodes back to the entries it was written from")
    void theRecipeSurvivesTheRoundTrip() {
        withSeededStore(dsl -> {
            seedGraph(dsl, GRAPH);
            StoreEntries.write(dsl, GRAPH, SubjectConfig.of(recipe()), FIRST);

            // The decode is the incumbent gatherer's and it reads what this writer wrote, so the
            // kind taxonomy is spelled in two places until the incumbent goes. This case is what
            // holds the two spellings to each other in the meantime; the relation's CHECK catches
            // only a value neither of them admits.
            var decoded = StoredRecipe.decode(dsl, GRAPH).orElseThrow();

            assertThat(decoded.bindings())
                .as("each entry recovers the arm it was written from, from the stored kind rather "
                    + "than by asking the filesystem about a stored string")
                .isEqualTo(recipe().bindings());
            assertThat(decoded.extensions()).isEqualTo(recipe().extensions());
        });
    }

    @Test
    @DisplayName("a subject that declared nothing writes nothing, rather than writing blanks")
    void absenceIsAMissingRow() {
        withSeededStore(dsl -> {
            seedGraph(dsl, GRAPH);
            StoreEntries.write(dsl, GRAPH, SubjectConfig.none(), FIRST);

            assertThat(census(dsl, GRAPH).values())
                .as("no row anywhere; a synthesised value here is a fact that can disagree with "
                    + "the run it claims to describe")
                .allMatch(count -> count == 0);
        });
    }

    @Test
    @DisplayName("a parameter the author removed loses its row on the next reading")
    void sweepsWhatTheRunNoLongerDeclares() {
        withSeededStore(dsl -> {
            seedGraph(dsl, GRAPH);
            StoreEntries.write(dsl, GRAPH, everything(), FIRST);

            // The same build file with three elements taken out: the tenant column, the unmount
            // beside the mount, and one lint rule. Each is a different way of going away, and none
            // of the three produces an incoming row for an upsert to land on.
            StoreEntries.write(dsl, GRAPH, new SubjectConfig(
                Optional.of(recipe()), Optional.of("com.example.jooq"), Optional.of("catalogue"),
                Optional.of(output()), Optional.empty(),
                LintConfig.validated(Set.of("field-names-camel-case"), List.of("Legacy*", "*Internal")),
                new SessionStateConfig.MethodHooks(
                    new SessionStateConfig.HookRef("com.example.Identity", "mount"),
                    Optional.empty())),
                SECOND);

            assertThat(dsl.fetchCount(STORE_GRAPH_TENANT_COLUMN,
                STORE_GRAPH_TENANT_COLUMN.GRAPH_NAME.eq(GRAPH)))
                .as("a single-valued parameter that went away is a graph with no row, which is "
                    + "the same state a graph that never had one is in")
                .isZero();
            assertThat(dsl.fetchCount(STORE_GRAPH_SESSION_UNMOUNT,
                STORE_GRAPH_SESSION_UNMOUNT.GRAPH_NAME.eq(GRAPH)))
                .as("the unmount goes and the mount stays, which is the supported mount-only "
                    + "configuration rather than a foreign key violation")
                .isZero();
            assertThat(dsl.fetchCount(STORE_GRAPH_SESSION_MOUNT,
                STORE_GRAPH_SESSION_MOUNT.GRAPH_NAME.eq(GRAPH)))
                .isOne();
            assertThat(dsl.select(STORE_GRAPH_LINT_DISABLED_RULE.RULE_ID)
                .from(STORE_GRAPH_LINT_DISABLED_RULE)
                .where(STORE_GRAPH_LINT_DISABLED_RULE.GRAPH_NAME.eq(GRAPH))
                .fetch(0, String.class))
                .as("a value-keyed row the reading did not touch, rather than one it overwrote")
                .containsExactly("field-names-camel-case");
        });
    }

    @Test
    @DisplayName("a recipe that lost its trailing entries loses their rows")
    void sweepsTheOrdinalsARecipeNoLongerReaches() {
        withSeededStore(dsl -> {
            seedGraph(dsl, GRAPH);
            StoreEntries.write(dsl, GRAPH, everything(), FIRST);

            var shorter = new SchemaRecipe(Path.of("/build/pom.xml"),
                List.of(SchemaRecipe.Binding.pattern("src/main/resources/**.graphqls")),
                List.of(".graphqls"));
            StoreEntries.write(dsl, GRAPH, SubjectConfig.of(shorter), SECOND);

            assertThat(dsl.select(STORE_GRAPH_SCHEMA_INPUT.ORDINAL)
                .from(STORE_GRAPH_SCHEMA_INPUT)
                .where(STORE_GRAPH_SCHEMA_INPUT.GRAPH_NAME.eq(GRAPH))
                .fetch(0, Integer.class))
                .as("ordinal 0 upserts and the two the shorter recipe never reaches are swept; "
                    + "keying by position is exactly what makes them invisible to the upsert")
                .containsExactly(0);
            assertThat(dsl.select(STORE_GRAPH_SCHEMA_EXTENSION.EXTENSION)
                .from(STORE_GRAPH_SCHEMA_EXTENSION)
                .where(STORE_GRAPH_SCHEMA_EXTENSION.GRAPH_NAME.eq(GRAPH))
                .fetch(0, String.class))
                .containsExactly(".graphqls");
        });
    }

    @Test
    @DisplayName("reading the same configuration twice leaves the same rows, restamped")
    void writingTwiceRestampsRatherThanDuplicating() {
        withSeededStore(dsl -> {
            seedGraph(dsl, GRAPH);
            StoreEntries.write(dsl, GRAPH, everything(), FIRST);
            var afterFirst = census(dsl, GRAPH);

            // A second reading of an unchanged configuration, which is where a sweep predicate
            // that is wrong on the second pass shows it: rows deleted, duplicated, or left
            // carrying the older instant. The first pass cannot fail that way, having nothing
            // to sweep.
            StoreEntries.write(dsl, GRAPH, everything(), SECOND);

            assertThat(census(dsl, GRAPH))
                .as("the same configuration transcribes to the same rows however often it is read")
                .isEqualTo(afterFirst);
            assertThat(stamps(dsl, SECOND))
                .as("and every one of them belongs to the reading that just ran")
                .isEqualTo(afterFirst.values().stream().mapToInt(Integer::intValue).sum());
        });
    }

    @Test
    @DisplayName("one graph's reading leaves a sibling's configuration alone")
    void sweepsOnlyItsOwnPartition() {
        withSeededStore(dsl -> {
            seedGraph(dsl, GRAPH);
            seedGraph(dsl, SIBLING);
            StoreEntries.write(dsl, SIBLING, everything(), FIRST);

            // A different graph, a different instant, and a subject declaring nothing: the sweep
            // predicate that ignores the partition would empty the sibling here.
            StoreEntries.write(dsl, GRAPH, SubjectConfig.none(), SECOND);

            assertThat(census(dsl, SIBLING).values())
                .as("a shared store holds several modules, and a module's reading speaks for one")
                .allMatch(count -> count > 0);
        });
    }

    /** A subject with every parameter of the family declared. */
    private static SubjectConfig everything() {
        return new SubjectConfig(
            Optional.of(recipe()),
            Optional.of("com.example.jooq"),
            Optional.of("catalogue"),
            Optional.of(output()),
            Optional.of("tenant_id"),
            LintConfig.validated(Set.of("no-typename-prefix", "field-names-camel-case"),
                List.of("Legacy*", "*Internal")),
            new SessionStateConfig.MethodHooks(
                new SessionStateConfig.HookRef("com.example.Identity", "mount"),
                Optional.of(new SessionStateConfig.HookRef("com.example.Identity", "unmount"))));
    }

    /** One entry of each kind, so the taxonomy column has all three of its values to carry. */
    private static SchemaRecipe recipe() {
        return new SchemaRecipe(Path.of("/build/pom.xml"), List.of(
            SchemaRecipe.Binding.pattern("src/main/resources/**.graphqls"),
            new SchemaRecipe.Binding(
                new SchemaRecipe.Entry.Literal(SchemaSource.file(Path.of("/schema/extra.graphqls"))),
                Optional.of("extra"), Optional.of("from the extra file")),
            SchemaRecipe.Binding.literal(SchemaSource.named("bundled"))),
            List.of(".graphqls", ".graphql"));
    }

    private static OutputCoordinates output() {
        return new OutputCoordinates("com.example.generated", "com.example.jooq",
            Path.of("/build/generated"));
    }

    /**
     * The relations the transcription writes, listed rather than found by prefix: two relations
     * share the prefix and are not this gatherer's, and a relation missing from the list is one
     * these cases would pass without having looked at.
     */
    private static final List<Table<?>> TRANSCRIBED = List.of(
        STORE_GRAPH_SCHEMA_INPUT, STORE_GRAPH_SCHEMA_EXTENSION, STORE_GRAPH_SUPERGRAPH,
        STORE_GRAPH_OUTPUT, STORE_GRAPH_TENANT_COLUMN, STORE_GRAPH_LINT_DISABLED_RULE,
        STORE_GRAPH_LINT_EXCLUDED_TYPE, STORE_GRAPH_SESSION_MOUNT, STORE_GRAPH_SESSION_UNMOUNT);

    /** How many rows each relation holds for one graph. */
    private static Map<String, Integer> census(DSLContext dsl, String graph) {
        var counts = new LinkedHashMap<String, Integer>();
        for (Table<?> table : TRANSCRIBED) {
            counts.put(table.getName(), dsl.fetchCount(table,
                table.field(STORE_GRAPH_SCHEMA_INPUT.GRAPH_NAME).eq(graph)));
        }
        return counts;
    }

    /** Rows across the family carrying one reading's instant. */
    private static int stamps(DSLContext dsl, LocalDateTime touchedAt) {
        int total = 0;
        for (Table<?> table : TRANSCRIBED) {
            total += dsl.fetchCount(table,
                table.field(STORE_GRAPH_SCHEMA_INPUT.GRAPH_NAME).eq(GRAPH)
                    .and(table.field(STORE_GRAPH_SCHEMA_INPUT.TOUCHED_AT).eq(touchedAt)));
        }
        return total;
    }
}
