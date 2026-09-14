package no.sikt.graphitron.model.lint;

import no.sikt.graphitron.model.diagnostics.BuildWarning;
import no.sikt.graphitron.model.read.StoreHandle;
import org.jooq.DSLContext;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;

import static no.sikt.graphitron.model.test.SeededStore.derive;
import static no.sikt.graphitron.model.test.SeededStore.seedColumn;
import static no.sikt.graphitron.model.test.SeededStore.seedField;
import static no.sikt.graphitron.model.test.SeededStore.seedFieldReference;
import static no.sikt.graphitron.model.test.SeededStore.seedFieldReferenceStep;
import static no.sikt.graphitron.model.test.SeededStore.seedForeignKey;
import static no.sikt.graphitron.model.test.SeededStore.seedGraphSource;
import static no.sikt.graphitron.model.test.SeededStore.seedPrimaryKey;
import static no.sikt.graphitron.model.test.SeededStore.seedSource;
import static no.sikt.graphitron.model.test.SeededStore.seedTable;
import static no.sikt.graphitron.model.test.SeededStore.seedTableBinding;
import static no.sikt.graphitron.model.test.SeededStore.withSeededStore;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * What the fan-out producer mints from the view's rows: which population it asks about, which
 * verdicts word a finding, and what the finding says.
 *
 * <p>What each verdict <em>means</em> is
 * {@code no.sikt.graphitron.model.intent.ReferenceStepFanoutTest}'s, which witnesses all four arms
 * against seeded rows; the cases here are about the decode and the message over them.
 */
class ReferencePathFanoutTest {

    /**
     * The finding lands at the coordinate, names the multiplying element by position and the table
     * by name, and carries the remedy as a fix with no edits: there is no mechanical rewrite from a
     * fanning path to a set, so what an editor is offered is the pattern's name.
     */
    @Test
    void aFanningListFieldGetsAFindingNamingTheHopAndTheRemedy() {
        withFanningPath(dsl -> {
            var findings = findings(dsl, ExcludedTypes.of(List.of()));
            assertThat(findings).hasSize(1);
            var finding = findings.getFirst();
            assertThat(finding.rule()).isEqualTo(LintRule.REFERENCE_PATH_FANS_OUT);
            assertThat(finding.message())
                .contains("Film.notes")
                .contains("public.film_actor_note")
                .contains("at element 1");
            assertThat(finding.fix()).isPresent();
            assertThat(finding.fix().orElseThrow().description()).contains("DISTINCT");
            assertThat(finding.fix().orElseThrow().edits())
                .as("a fix the build could apply is not what this rule has to offer")
                .isEmpty();
        });
    }

    /**
     * The population is the list fields. A scalar field over the same path lowers to a capped
     * correlated subselect, one row however many the hop reaches, so fan-out there picks an
     * arbitrary row rather than repeating one: a different defect with a different remedy, and not
     * this rule's to word.
     */
    @Test
    void aScalarFieldOverTheSamePathIsNotThisRulesToWord() {
        withCatalog(dsl -> {
            seedTableBinding(dsl, GRAPH, "Film", "film");
            seedScalarPath(dsl, "Film", "noteText", "film_actor_film_id_fkey",
                "film_actor_note_pair_fkey", "film_actor_note_actor_id_fkey");

            assertThat(findings(dsl, ExcludedTypes.of(List.of()))).isEmpty();
        });
    }

    /**
     * A type the consumer excluded by name is not linted by this rule either. The engine applies
     * the same globs before its walk; a finding minted outside that walk at a coordinate on an
     * excluded type would read as a bug, so the producer applies them at the coordinate, through
     * the engine's own matcher rather than a second copy of the rule.
     */
    @Test
    void anExcludedTypeIsNotLintedByThisRuleEither() {
        withFanningPath(dsl -> {
            assertThat(findings(dsl, ExcludedTypes.of(List.of("Actor*"))))
                .as("a glob matching some other type leaves this coordinate linted")
                .isNotEmpty();
            assertThat(findings(dsl, ExcludedTypes.of(List.of("Fil?"))))
                .as("the glob matches the enclosing type")
                .isEmpty();
        });
    }

    /** A path whose intermediate is a pure join table mints nothing; the list is a set already. */
    @Test
    void aCoveredIntermediateMintsNothing() {
        withCatalog(dsl -> {
            seedTableBinding(dsl, GRAPH, "Film", "film");
            seedListPath(dsl, "Film", "actors",
                "film_actor_film_id_fkey", "film_actor_actor_id_fkey");

            assertThat(findings(dsl, ExcludedTypes.of(List.of()))).isEmpty();
        });
    }

    /**
     * The decode is total over the view's vocabulary and drifts loudly. The view's arms and this
     * enum have to move together, so a value the enum does not name is a build bug rather than a
     * row to skip; every arm the view can produce is witnessed against seeded rows by the view's
     * own test.
     */
    @Test
    void anUnknownVerdictIsDriftAndThrows() {
        assertThat(Arrays.stream(ReferencePathFanout.Verdict.values())
            .map(v -> ReferencePathFanout.Verdict.of(v.name())))
            .containsExactly(ReferencePathFanout.Verdict.values());
        assertThatThrownBy(() -> ReferencePathFanout.Verdict.of("SOMETHING_ELSE"))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("SOMETHING_ELSE")
            .hasMessageContaining("must move together");
    }

    // ===== Helpers =====

    private static final String GRAPH = "g";
    private static final String PKG = "pkg";
    private static final String PUBLIC = "public";

    private static List<BuildWarning.LintFinding> findings(DSLContext dsl, ExcludedTypes excluded) {
        derive(dsl);
        return ReferencePathFanout.findings(new StoreHandle(dsl, GRAPH), excluded);
    }

    /**
     * The catalog's fanning shape: a junction carrying a key column neither hop binds, reached
     * through a pure join table so the same path also holds a covered intermediate.
     */
    private static void withFanningPath(Consumer<DSLContext> body) {
        withCatalog(dsl -> {
            seedTableBinding(dsl, GRAPH, "Film", "film");
            seedListPath(dsl, "Film", "notes", "film_actor_film_id_fkey",
                "film_actor_note_pair_fkey", "film_actor_note_actor_id_fkey");
            body.accept(dsl);
        });
    }

    private static void withCatalog(Consumer<DSLContext> body) {
        withSeededStore(GRAPH, dsl -> {
            seedSource(dsl, PKG, "JOOQ_SCHEMA");
            seedGraphSource(dsl, GRAPH, PKG);

            table(dsl, "film", "film_id");
            seedPrimaryKey(dsl, PKG, PUBLIC, "film", "film_pkey", "film_id");
            table(dsl, "actor", "actor_id");
            seedPrimaryKey(dsl, PKG, PUBLIC, "actor", "actor_pkey", "actor_id");

            table(dsl, "film_actor", "film_id", "actor_id");
            seedPrimaryKey(dsl, PKG, PUBLIC, "film_actor", "film_actor_pkey",
                "actor_id", "film_id");
            seedForeignKey(dsl, PKG, PUBLIC, "film_actor", "film_actor_film_id_fkey",
                "film", "film_pkey", "film_id");
            seedForeignKey(dsl, PKG, PUBLIC, "film_actor", "film_actor_actor_id_fkey",
                "actor", "actor_pkey", "actor_id");

            table(dsl, "film_actor_note", "actor_id", "film_id", "lang_code");
            seedPrimaryKey(dsl, PKG, PUBLIC, "film_actor_note", "film_actor_note_pkey",
                "actor_id", "film_id", "lang_code");
            seedForeignKey(dsl, PKG, PUBLIC, "film_actor_note", "film_actor_note_pair_fkey",
                "film_actor", "film_actor_pkey", "actor_id", "film_id");
            seedForeignKey(dsl, PKG, PUBLIC, "film_actor_note", "film_actor_note_actor_id_fkey",
                "actor", "actor_pkey", "actor_id");

            body.accept(dsl);
        });
    }

    private static void table(DSLContext dsl, String tableName, String... columnNames) {
        seedTable(dsl, PKG, PUBLIC, tableName);
        for (int ordinal = 0; ordinal < columnNames.length; ordinal++) {
            seedColumn(dsl, PKG, PUBLIC, tableName, columnNames[ordinal], ordinal,
                columnNames[ordinal].toUpperCase());
        }
    }

    /** A list field carrying one {@code @reference} whose elements each spell a key. */
    private static void seedListPath(DSLContext dsl, String typeName, String fieldName,
                                     String... keyRefs) {
        seedPath(dsl, typeName, fieldName, true, keyRefs);
    }

    /** The same path on a field returning one value rather than a list of them. */
    private static void seedScalarPath(DSLContext dsl, String typeName, String fieldName,
                                       String... keyRefs) {
        seedPath(dsl, typeName, fieldName, false, keyRefs);
    }

    private static void seedPath(DSLContext dsl, String typeName, String fieldName, boolean isList,
                                 String... keyRefs) {
        seedField(dsl, GRAPH, typeName, fieldName, "String", isList);
        seedFieldReference(dsl, GRAPH, typeName, fieldName, 0);
        for (int position = 0; position < keyRefs.length; position++) {
            seedFieldReferenceStep(dsl, GRAPH, typeName, fieldName, 0, position, null,
                keyRefs[position]);
        }
    }
}
