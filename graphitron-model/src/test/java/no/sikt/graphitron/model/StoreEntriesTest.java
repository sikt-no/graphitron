package no.sikt.graphitron.model;

import no.sikt.graphitron.model.capture.config.StoredRecipe;
import no.sikt.graphitron.model.capture.store.StoreEntries;
import no.sikt.graphitron.model.config.ClasspathEntry;
import no.sikt.graphitron.model.config.WorkspaceModule;
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

import static no.sikt.graphitron.model.Tables.STORE_GRAPH_DEPENDENCY;
import static no.sikt.graphitron.model.Tables.STORE_GRAPH_MODULE;
import static no.sikt.graphitron.model.Tables.STORE_GRAPH_MODULE_ENTRY;
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
 * The recipe and the classpath halves of the configuration transcription: what a run was told to
 * read, and that it stopped being told to read something.
 *
 * <p>The rest of the family is stated by fact documents, captured through the real run under the
 * configuration each states with {@code @withConfiguration}: {@code configuration},
 * {@code configuration-none} and the stepped {@code configuration-sweep}. The recipe and the
 * classpath stay here because a document's are the harness's own, its own files and the classpath
 * corpus, so no document can state another. So does the one case about a sibling graph, the
 * documents being checked one graph at a time.
 *
 * <p>The sweep is the half a writer gets wrong: an entry deleted from the build file produces no
 * row to overwrite, so a store that only ever upserts keeps answering with the configuration of a
 * run that has exited.
 */
class StoreEntriesTest {

    private static final String GRAPH = "config";
    private static final String SIBLING = "sibling";
    private static final LocalDateTime FIRST = LocalDateTime.of(2026, 1, 1, 12, 0);
    private static final LocalDateTime SECOND = FIRST.plusMinutes(1);

    @Test
    @DisplayName("the recipe lands as the run held it")
    void transcribesTheRecipe() {
        withSeededStore(dsl -> {
            seedGraph(dsl, GRAPH);
            StoreEntries.write(dsl, GRAPH, everything(), classpath(), FIRST);

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

        });
    }

    @Test
    @DisplayName("the recipe decodes back to the entries it was written from")
    void theRecipeSurvivesTheRoundTrip() {
        withSeededStore(dsl -> {
            seedGraph(dsl, GRAPH);
            StoreEntries.write(dsl, GRAPH, SubjectConfig.of(recipe()), List.of(), FIRST);

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
    @DisplayName("no recipe and no classpath write no rows, rather than empty ones")
    void absenceIsAMissingRow() {
        withSeededStore(dsl -> {
            seedGraph(dsl, GRAPH);
            StoreEntries.write(dsl, GRAPH, SubjectConfig.none(), List.of(), FIRST);

            assertThat(census(dsl, GRAPH, HELD).values())
                .as("no row anywhere; a synthesised value here is a fact that can disagree with "
                    + "the run it claims to describe")
                .allMatch(count -> count == 0);
        });
    }

    @Test
    @DisplayName("a dependency the build stopped declaring loses its row on the next reading")
    void sweepsADependencyTheRunNoLongerDeclares() {
        withSeededStore(dsl -> {
            seedGraph(dsl, GRAPH);
            StoreEntries.write(dsl, GRAPH, everything(), classpath(), FIRST);

            // The same build with its snapshot dependency taken out, which produces no incoming
            // row for an upsert to land on.
            StoreEntries.write(dsl, GRAPH, everything(), classpath().subList(0, 2), SECOND);

            assertThat(dsl.select(STORE_GRAPH_DEPENDENCY.SOURCE_NAME)
                .from(STORE_GRAPH_DEPENDENCY)
                .where(STORE_GRAPH_DEPENDENCY.GRAPH_NAME.eq(GRAPH))
                .fetch(0, String.class))
                .as("a dependency the build stopped declaring")
                .containsExactlyInAnyOrder("/build/target/classes", "/repo/jooq-3.20.11.jar");
            assertThat(dsl.select(STORE_GRAPH_MODULE.COORDINATE)
                .from(STORE_GRAPH_MODULE)
                .where(STORE_GRAPH_MODULE.GRAPH_NAME.eq(GRAPH))
                .fetch(0, String.class))
                .as("and the module only it provided")
                .containsExactly("com.example:own");
        });
    }

    @Test
    @DisplayName("each classpath entry is a dependency row saying what the build resolved it as")
    void transcribesTheClasspath() {
        withSeededStore(dsl -> {
            seedGraph(dsl, GRAPH);
            StoreEntries.write(dsl, GRAPH, SubjectConfig.none(), classpath(), FIRST);

            assertThat(dsl.select(STORE_GRAPH_DEPENDENCY.SOURCE_NAME, STORE_GRAPH_DEPENDENCY.KIND,
                    STORE_GRAPH_DEPENDENCY.ORIGIN, STORE_GRAPH_DEPENDENCY.COORDINATE)
                .from(STORE_GRAPH_DEPENDENCY)
                .where(STORE_GRAPH_DEPENDENCY.GRAPH_NAME.eq(GRAPH))
                .orderBy(STORE_GRAPH_DEPENDENCY.SOURCE_NAME)
                .fetch(r -> List.of(r.value1(), r.value2(), r.value3(), String.valueOf(r.value4()))))
                .containsExactly(
                    List.of("/build/target/classes", "DIRECTORY", "PROJECT", "null"),
                    List.of("/repo/jooq-3.20.11.jar", "RELEASE", "DECLARED", "org.jooq:jooq"),
                    List.of("/repo/model-1.0-SNAPSHOT.jar", "SNAPSHOT", "REACTOR",
                        "com.example:model"));
        });
    }

    @Test
    @DisplayName("each workspace module the classpath reads is a row, and each entry names its module")
    void transcribesTheModulesTheClasspathReads() {
        withSeededStore(dsl -> {
            seedGraph(dsl, GRAPH);
            StoreEntries.write(dsl, GRAPH, SubjectConfig.none(), classpath(), FIRST);

            assertThat(dsl.select(STORE_GRAPH_MODULE.COORDINATE, STORE_GRAPH_MODULE.BASE_DIRECTORY,
                    STORE_GRAPH_MODULE.CLASSES_DIRECTORY)
                .from(STORE_GRAPH_MODULE)
                .where(STORE_GRAPH_MODULE.GRAPH_NAME.eq(GRAPH))
                .orderBy(STORE_GRAPH_MODULE.COORDINATE)
                .fetch(r -> List.of(r.value1(), r.value2(), r.value3())))
                .as("the third-party jar is no module's")
                .containsExactly(
                    List.of("com.example:model", "/work/model", "/work/model/target/classes"),
                    List.of("com.example:own", "/build", "/build/target/classes"));
            assertThat(dsl.select(STORE_GRAPH_MODULE_ENTRY.SOURCE_NAME, STORE_GRAPH_MODULE_ENTRY.COORDINATE)
                .from(STORE_GRAPH_MODULE_ENTRY)
                .where(STORE_GRAPH_MODULE_ENTRY.GRAPH_NAME.eq(GRAPH))
                .orderBy(STORE_GRAPH_MODULE_ENTRY.SOURCE_NAME)
                .fetch(r -> List.of(r.value1(), r.value2())))
                .as("an installed copy is a form of the module as much as its own output is")
                .containsExactly(
                    List.of("/build/target/classes", "com.example:own"),
                    List.of("/repo/model-1.0-SNAPSHOT.jar", "com.example:model"));
        });
    }

    @Test
    @DisplayName("a recipe that lost its trailing entries loses their rows")
    void sweepsTheOrdinalsARecipeNoLongerReaches() {
        withSeededStore(dsl -> {
            seedGraph(dsl, GRAPH);
            StoreEntries.write(dsl, GRAPH, everything(), classpath(), FIRST);

            var shorter = new SchemaRecipe(Path.of("/build/pom.xml"),
                List.of(SchemaRecipe.Binding.pattern("src/main/resources/**.graphqls")),
                List.of(".graphqls"));
            StoreEntries.write(dsl, GRAPH, SubjectConfig.of(shorter), List.of(), SECOND);

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
    @DisplayName("reading the same recipe and classpath twice leaves the same rows, restamped")
    void writingTwiceRestampsRatherThanDuplicating() {
        withSeededStore(dsl -> {
            seedGraph(dsl, GRAPH);
            StoreEntries.write(dsl, GRAPH, everything(), classpath(), FIRST);
            var afterFirst = census(dsl, GRAPH, HELD);

            // A second reading of an unchanged configuration, which is where a sweep predicate
            // that is wrong on the second pass shows it: rows deleted, duplicated, or left
            // carrying the older instant. The first pass cannot fail that way, having nothing
            // to sweep.
            StoreEntries.write(dsl, GRAPH, everything(), classpath(), SECOND);

            assertThat(census(dsl, GRAPH, HELD))
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
            StoreEntries.write(dsl, SIBLING, everything(), classpath(), FIRST);

            // A different graph, a different instant, and a subject declaring nothing: the sweep
            // predicate that ignores the partition would empty the sibling here.
            StoreEntries.write(dsl, GRAPH, SubjectConfig.none(), List.of(), SECOND);

            assertThat(census(dsl, SIBLING, FAMILY).values())
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

    /** One entry of each kind, the snapshot last so a sweep case can drop it by truncating. */
    private static List<ClasspathEntry> classpath() {
        return List.of(
            new ClasspathEntry(Path.of("/build/target/classes"), ClasspathEntry.Origin.PROJECT,
                null, null, ClasspathEntry.Kind.DIRECTORY,
                WorkspaceModule.conventional("com.example:own", Path.of("/build"))),
            new ClasspathEntry(Path.of("/repo/jooq-3.20.11.jar"), ClasspathEntry.Origin.DECLARED,
                "org.jooq:jooq", null, ClasspathEntry.Kind.RELEASE),
            new ClasspathEntry(Path.of("/repo/model-1.0-SNAPSHOT.jar"),
                ClasspathEntry.Origin.REACTOR, "com.example:model", null,
                ClasspathEntry.Kind.SNAPSHOT,
                WorkspaceModule.conventional("com.example:model", Path.of("/work/model"))));
    }

    private static OutputCoordinates output() {
        return new OutputCoordinates("com.example.generated", "com.example.jooq",
            Path.of("/build/generated"));
    }

    /** The recipe's and the classpath's relations, which are this class's subject. */
    private static final List<Table<?>> HELD = List.of(
        STORE_GRAPH_SCHEMA_INPUT, STORE_GRAPH_SCHEMA_EXTENSION, STORE_GRAPH_DEPENDENCY);

    /**
     * Every relation the transcription writes, listed rather than found by prefix: two relations
     * share the prefix and are not this gatherer's, and a relation missing from the list is one
     * the sibling case would pass without having looked at.
     */
    private static final List<Table<?>> FAMILY = List.of(
        STORE_GRAPH_SCHEMA_INPUT, STORE_GRAPH_SCHEMA_EXTENSION, STORE_GRAPH_SUPERGRAPH,
        STORE_GRAPH_OUTPUT, STORE_GRAPH_TENANT_COLUMN, STORE_GRAPH_LINT_DISABLED_RULE,
        STORE_GRAPH_LINT_EXCLUDED_TYPE, STORE_GRAPH_SESSION_MOUNT, STORE_GRAPH_SESSION_UNMOUNT,
        STORE_GRAPH_DEPENDENCY, STORE_GRAPH_MODULE, STORE_GRAPH_MODULE_ENTRY);

    /** How many rows each of {@code relations} holds for one graph. */
    private static Map<String, Integer> census(DSLContext dsl, String graph, List<Table<?>> relations) {
        var counts = new LinkedHashMap<String, Integer>();
        for (Table<?> table : relations) {
            counts.put(table.getName(), dsl.fetchCount(table,
                table.field(STORE_GRAPH_SCHEMA_INPUT.GRAPH_NAME).eq(graph)));
        }
        return counts;
    }

    /** Rows across this class's relations carrying one reading's instant. */
    private static int stamps(DSLContext dsl, LocalDateTime touchedAt) {
        int total = 0;
        for (Table<?> table : HELD) {
            total += dsl.fetchCount(table,
                table.field(STORE_GRAPH_SCHEMA_INPUT.GRAPH_NAME).eq(GRAPH)
                    .and(table.field(STORE_GRAPH_SCHEMA_INPUT.TOUCHED_AT).eq(touchedAt)));
        }
        return total;
    }
}
