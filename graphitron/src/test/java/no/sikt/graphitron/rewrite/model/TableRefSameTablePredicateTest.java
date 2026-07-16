package no.sikt.graphitron.rewrite.model;

import no.sikt.graphitron.javapoet.ClassName;
import no.sikt.graphitron.rewrite.test.tier.UnitTier;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link TableRef#sameTable(String)} / {@link TableRef#denotesSameTableAs(TableRef)}
 * are the canonical case-insensitive table-identity comparison. {@code tableName()} stays the
 * case-preserved verbatim {@code @table(name:)} echo, so the same logical table can carry the
 * Oracle-style UPPERCASE {@code @table} casing on one ref and the lowercase jOOQ
 * {@code Table.getName()} casing on another; the predicate must match across that divergence and
 * stay null-safe.
 *
 * <p>{@link TableRef#denotesSameTableAs(TableRef)} now routes through the reified jOOQ
 * {@code tableClass} identity when both refs carry one, so a schema-qualified {@code @table} echo
 * matches jOOQ's unqualified canonical name across colliding schemas, and two same-bare-name refs
 * from different schemas are correctly distinct. The name-compare fallback survives only for
 * fixture-built classless refs. The three predicate arms are pinned below so a silent regression to
 * bare-name behaviour cannot pass.
 */
@UnitTier
class TableRefSameTablePredicateTest {

    /** Only {@code tableName} participates in the name-fallback predicate; the rest is irrelevant here. */
    private static TableRef ref(String tableName) {
        return new TableRef(tableName, null, null, null, null, List.of(), List.of());
    }

    /** Carries a reified {@code tableClass} so the identity arm of {@code denotesSameTableAs} engages. */
    private static TableRef ref(String tableName, ClassName tableClass) {
        return new TableRef(tableName, null, tableClass, null, null, List.of(), List.of());
    }

    @Test
    void sameTable_matchesIgnoringCase() {
        var film = ref("FILM"); // verbatim Oracle-style @table casing
        assertThat(film.sameTable("FILM")).isTrue();
        assertThat(film.sameTable("film")).isTrue(); // lowercase jOOQ casing
        assertThat(film.sameTable("FiLm")).isTrue();
        assertThat(film.sameTable("actor")).isFalse();
    }

    @Test
    void sameTable_nullArgIsNotThisTable() {
        assertThat(ref("film").sameTable(null)).isFalse();
    }

    @Test
    void denotesSameTableAs_matchesIgnoringCaseBothDirections() {
        var upper = ref("FILM");
        var lower = ref("film");
        assertThat(upper.denotesSameTableAs(lower)).isTrue();
        assertThat(lower.denotesSameTableAs(upper)).isTrue();
        assertThat(upper.denotesSameTableAs(ref("actor"))).isFalse();
    }

    @Test
    void denotesSameTableAs_nullArgIsNotThisTable() {
        assertThat(ref("film").denotesSameTableAs(null)).isFalse();
    }

    // --- identity arm ---

    /**
     * Arm 1: same {@code tableClass}, divergent names/casing. A schema-qualified {@code @table}
     * echo on one side vs. jOOQ's unqualified canonical name on the other still denotes the same
     * table, because the comparison keys on the reified class identity, not the name string.
     */
    @Test
    void denotesSameTableAs_sameTableClassDivergentNames_isTrue() {
        var eventClass = ClassName.get("com.example.multischema_a.tables", "Event");
        var qualifiedEcho = ref("multischema_a.event", eventClass); // verbatim @table echo
        var jooqCanonical = ref("event", eventClass);               // jOOQ Table.getName()
        assertThat(qualifiedEcho.denotesSameTableAs(jooqCanonical)).isTrue();
        assertThat(jooqCanonical.denotesSameTableAs(qualifiedEcho)).isTrue();
    }

    /**
     * Arm 2 (the cross-schema case): same bare name, different {@code tableClass}. Two {@code event}
     * tables in distinct schemas are distinct tables. Without this arm a regression to bare-name
     * behaviour, which would report these equal, passes silently.
     */
    @Test
    void denotesSameTableAs_sameBareNameDifferentTableClass_isFalse() {
        var aEvent = ref("event", ClassName.get("com.example.multischema_a.tables", "Event"));
        var bEvent = ref("event", ClassName.get("com.example.multischema_b.tables", "Event"));
        assertThat(aEvent.denotesSameTableAs(bEvent)).isFalse();
        assertThat(bEvent.denotesSameTableAs(aEvent)).isFalse();
    }

    /**
     * Arm 3: when either side lacks a {@code tableClass}, the predicate falls back to the
     * case-insensitive name compare (the fixture-built-partial-ref seam). The
     * {@code denotesSameTableAs_matchesIgnoringCaseBothDirections} test already exercises the
     * both-classless direction; this pins the mixed null-class-vs-class direction explicitly.
     */
    @Test
    void denotesSameTableAs_oneSideClassless_fallsBackToNameCompare() {
        var classful = ref("FILM", ClassName.get("com.example.schema.tables", "Film"));
        var classless = ref("film"); // fixture-built partial ref
        assertThat(classful.denotesSameTableAs(classless)).isTrue();
        assertThat(classless.denotesSameTableAs(classful)).isTrue();
        assertThat(classful.denotesSameTableAs(ref("actor"))).isFalse();
    }
}
