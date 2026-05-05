package no.sikt.graphitron.rewrite;

import no.sikt.graphitron.javapoet.ClassName;
import no.sikt.graphitron.rewrite.test.tier.UnitTier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * R78 phase 2a coverage for {@link JooqCatalog}'s strict-resolution multi-schema API:
 * {@link JooqCatalog#parseQualifiedTableName}, {@link JooqCatalog#findTable(String)},
 * {@link JooqCatalog#findTable(String, String)}, {@link JooqCatalog#findCandidateSchemasFor},
 * and {@link JooqCatalog.TableEntry}'s typed accessors ({@code tableClass},
 * {@code recordClass}, {@code constantsClass}, {@code pkColumnRefs}).
 *
 * <p>The fixture is the multi-schema jOOQ codegen output produced by the
 * {@code jooq-codegen-multischema-fixture} execution in {@code graphitron-sakila-db}
 * (R78 phase 1): two schemas ({@code multischema_a}, {@code multischema_b}) sharing
 * one table name ({@code event}) and connected by a cross-schema FK
 * ({@code multischema_b.gadget -> multischema_a.widget}). Single-schema fixtures elsewhere
 * cannot exercise the ambiguity-policy or qualified-name branches because their tables
 * never collide.
 */
@UnitTier
class JooqCatalogMultiSchemaTest {

    private static final String MULTI_PACKAGE = "no.sikt.graphitron.rewrite.multischemafixture";

    private static JooqCatalog multi() {
        return new JooqCatalog(MULTI_PACKAGE);
    }

    // ---- parseQualifiedTableName ----

    @Test
    void parseQualifiedTableName_unqualified_carriesTableOnly() {
        var parsed = JooqCatalog.parseQualifiedTableName("widget");
        assertThat(parsed).isPresent();
        assertThat(parsed.get().schema()).isEmpty();
        assertThat(parsed.get().table()).isEqualTo("widget");
        assertThat(parsed.get().isQualified()).isFalse();
    }

    @Test
    void parseQualifiedTableName_qualified_splitsOnFirstDot() {
        var parsed = JooqCatalog.parseQualifiedTableName("multischema_a.event");
        assertThat(parsed).isPresent();
        assertThat(parsed.get().schema()).hasValue("multischema_a");
        assertThat(parsed.get().table()).isEqualTo("event");
        assertThat(parsed.get().isQualified()).isTrue();
    }

    @Test
    void parseQualifiedTableName_multipleDots_splitsOnFirstAndKeepsRest() {
        var parsed = JooqCatalog.parseQualifiedTableName("a.b.c");
        assertThat(parsed).isPresent();
        assertThat(parsed.get().schema()).hasValue("a");
        assertThat(parsed.get().table()).isEqualTo("b.c");
    }

    @ParameterizedTest
    @CsvSource({
        "''",            // empty string
        "'   '",         // blank
        "'widget.'",     // empty table half
        "'.widget'"      // empty schema half
    })
    void parseQualifiedTableName_malformed_returnsEmpty(String input) {
        assertThat(JooqCatalog.parseQualifiedTableName(input)).isEmpty();
    }

    @Test
    void parseQualifiedTableName_null_returnsEmpty() {
        assertThat(JooqCatalog.parseQualifiedTableName(null)).isEmpty();
    }

    // ---- findTable(String) — unqualified, unique table ----

    @Test
    void findTable_unqualifiedUnique_resolvesToSingleSchema() {
        var found = multi().findTable("widget");
        assertThat(found).isPresent();
        assertThat(found.get().table().getSchema().getName()).isEqualTo("multischema_a");
        assertThat(found.get().table().getName()).isEqualTo("widget");
    }

    @Test
    void findTable_unqualifiedUnique_caseInsensitive() {
        assertThat(multi().findTable("WIDGET")).isPresent();
        assertThat(multi().findTable("Widget")).isPresent();
    }

    // ---- findTable(String) — unqualified, ambiguous ----

    @Test
    void findTable_unqualifiedAmbiguous_returnsEmpty() {
        // 'event' is defined in both multischema_a and multischema_b.
        // Strict resolution requires qualification — no first-schema-wins fallback.
        assertThat(multi().findTable("event")).isEmpty();
    }

    // ---- findTable(String) — qualified ----

    @Test
    void findTable_qualified_resolvesToNamedSchema() {
        var aEvent = multi().findTable("multischema_a.event");
        var bEvent = multi().findTable("multischema_b.event");
        assertThat(aEvent).isPresent();
        assertThat(bEvent).isPresent();
        assertThat(aEvent.get().table().getSchema().getName()).isEqualTo("multischema_a");
        assertThat(bEvent.get().table().getSchema().getName()).isEqualTo("multischema_b");
        // The two qualified resolutions yield distinct tables.
        assertThat(aEvent.get().table().getRecordType())
            .isNotEqualTo(bEvent.get().table().getRecordType());
    }

    @Test
    void findTable_qualifiedSchemaUnknown_returnsEmpty() {
        assertThat(multi().findTable("nonexistent.widget")).isEmpty();
    }

    @Test
    void findTable_qualifiedTableMissingFromSchema_returnsEmpty() {
        // 'gadget' lives in multischema_b only.
        assertThat(multi().findTable("multischema_a.gadget")).isEmpty();
    }

    @Test
    void findTable_qualifiedCaseInsensitiveBothHalves() {
        assertThat(multi().findTable("MULTISCHEMA_A.WIDGET")).isPresent();
        assertThat(multi().findTable("MultiSchema_B.Event")).isPresent();
    }

    // ---- findTable(schema, table) — two-arg ----

    @Test
    void findTable_twoArg_scopesToNamedSchemaDirectly() {
        var entry = multi().findTable("multischema_b", "event");
        assertThat(entry).isPresent();
        assertThat(entry.get().table().getSchema().getName()).isEqualTo("multischema_b");
    }

    @Test
    void findTable_twoArg_unknownSchema_returnsEmpty() {
        assertThat(multi().findTable("nonexistent", "widget")).isEmpty();
    }

    @Test
    void findTable_twoArg_tableNotInNamedSchema_returnsEmpty() {
        // gadget exists, but only in multischema_b.
        assertThat(multi().findTable("multischema_a", "gadget")).isEmpty();
    }

    // ---- findCandidateSchemasFor ----

    @Test
    void findCandidateSchemasFor_unique_returnsSingleton() {
        assertThat(multi().findCandidateSchemasFor("widget"))
            .containsExactly("multischema_a");
    }

    @Test
    void findCandidateSchemasFor_ambiguous_returnsBothSchemas() {
        assertThat(multi().findCandidateSchemasFor("event"))
            .containsExactlyInAnyOrder("multischema_a", "multischema_b");
    }

    @Test
    void findCandidateSchemasFor_unknown_returnsEmpty() {
        assertThat(multi().findCandidateSchemasFor("nonexistent")).isEmpty();
    }

    @Test
    void findCandidateSchemasFor_caseInsensitive() {
        assertThat(multi().findCandidateSchemasFor("WIDGET")).containsExactly("multischema_a");
        assertThat(multi().findCandidateSchemasFor("EVENT"))
            .containsExactlyInAnyOrder("multischema_a", "multischema_b");
    }

    // ---- TableEntry typed accessors ----

    @Test
    void tableEntry_tableClass_returnsSchemaQualifiedFqn() {
        var entry = multi().findTable("multischema_a", "widget").orElseThrow();
        assertThat(entry.tableClass())
            .isEqualTo(ClassName.get("no.sikt.graphitron.rewrite.multischemafixture.multischema_a.tables", "Widget"));
    }

    @Test
    void tableEntry_recordClass_returnsSchemaQualifiedRecordFqn() {
        var entry = multi().findTable("multischema_a", "widget").orElseThrow();
        assertThat(entry.recordClass())
            .isEqualTo(ClassName.get(
                "no.sikt.graphitron.rewrite.multischemafixture.multischema_a.tables.records",
                "WidgetRecord"));
    }

    @Test
    void tableEntry_constantsClass_returnsSchemaTablesClassFqn() {
        var entry = multi().findTable("multischema_a", "widget").orElseThrow();
        assertThat(entry.constantsClass())
            .isEqualTo(Optional.of(ClassName.get(
                "no.sikt.graphitron.rewrite.multischemafixture.multischema_a", "Tables")));
    }

    @Test
    void tableEntry_constantsClass_followsSchemaForCrossSchemaTable() {
        // gadget is in B; constants class must point to B's Tables, not A's.
        var entry = multi().findTable("multischema_b", "gadget").orElseThrow();
        assertThat(entry.constantsClass())
            .isEqualTo(Optional.of(ClassName.get(
                "no.sikt.graphitron.rewrite.multischemafixture.multischema_b", "Tables")));
    }

    @Test
    void tableEntry_pkColumnRefs_carriesPkInOrder() {
        var entry = multi().findTable("multischema_a", "widget").orElseThrow();
        assertThat(entry.pkColumnRefs())
            .extracting(c -> c.sqlName())
            .containsExactly("widget_id");
    }

    @Test
    void tableEntry_pkColumnRefs_compositeFkFromCrossSchemaSide() {
        // gadget has a single-column PK (gadget_id); also has FK to widget but FK columns
        // are not PK columns. Sanity check that pkColumnRefs reflects PK only.
        var entry = multi().findTable("multischema_b", "gadget").orElseThrow();
        assertThat(entry.pkColumnRefs())
            .extracting(c -> c.sqlName())
            .containsExactly("gadget_id");
    }

    // ---- Cross-schema FK shape (motivates Phase 3 ForeignKeyRef) ----

    @Test
    void crossSchemaFk_fkHolderSchemaIsB() {
        // The gadget→widget FK is held on multischema_b (FK-holder side), targeting
        // multischema_a.widget. Phase 3 ForeignKeyRef will route the Keys-class lookup
        // to the FK-holder's schema (multischema_b) — this test pins jOOQ's reporting
        // shape so the Phase 3 migration has a stable contract to lift over.
        var fk = multi().findForeignKey("gadget_widget_id_fkey");
        assertThat(fk).isPresent();
        assertThat(fk.get().getTable().getSchema().getName()).isEqualTo("multischema_b");
        assertThat(fk.get().getKey().getTable().getSchema().getName()).isEqualTo("multischema_a");
    }

    // ---- findForeignKeyByName: Phase 3 typed FK reference ----

    @Test
    void findForeignKeyByName_returnsSchemaSegmentedKeysClass() {
        // The cross-schema FK is declared on multischema_b's Keys class — this is the
        // multi-schema bug R78 fixes: a per-emit-site `ClassName.get(jooqPackage, "Keys")`
        // with jooqPackage = root would compile to the non-existent root.Keys (no Keys
        // class in the root package under multi-schema codegen). The typed lookup picks
        // the FK-holder schema's Keys class.
        var ref = multi().findForeignKeyByName("gadget_widget_id_fkey");
        assertThat(ref).isPresent();
        assertThat(ref.get().keysClass()).isEqualTo(ClassName.get(
            "no.sikt.graphitron.rewrite.multischemafixture.multischema_b", "Keys"));
        assertThat(ref.get().sqlName()).isEqualToIgnoringCase("gadget_widget_id_fkey");
        // Stock JavaGenerator names the constant <TABLE>__<FK_NAME> (uppercased), not FK_<...>;
        // pin the upper-cased SQL constraint name as the suffix to avoid coupling to the table prefix.
        assertThat(ref.get().constantName()).endsWith("GADGET_WIDGET_ID_FKEY");
    }

    @Test
    void findForeignKeyByName_unknownConstraintReturnsEmpty() {
        assertThat(multi().findForeignKeyByName("not_a_fk")).isEmpty();
    }

    // ---- BuildContext.unknownTableRejection: ambiguity-aware rejection wrap ----

    @Test
    void unknownTableRejection_unqualifiedAmbiguous_namesSchemasAndQualifiedForms() {
        var ctx = new BuildContext(null, multi(), null);
        var rejection = ctx.unknownTableRejection("event");
        // Author-error structural variant: rule violation with structured prose, not a closed-set
        // "did you mean" lookup. The candidate-hint shape doesn't fit because the user spelled
        // the name correctly; what they need is a qualified form, not a typo correction.
        assertThat(rejection).isInstanceOf(no.sikt.graphitron.rewrite.model.Rejection.AuthorError.Structural.class);
        assertThat(rejection.message())
            .contains("@table(name: 'event') is ambiguous: defined in schemas")
            .contains("multischema_a")
            .contains("multischema_b")
            .contains("'multischema_a.event'")
            .contains("'multischema_b.event'");
    }

    @Test
    void unknownTableRejection_unqualifiedMissing_fallsThroughToUnknownTable() {
        var ctx = new BuildContext(null, multi(), null);
        var rejection = ctx.unknownTableRejection("nonexistent");
        // Missing-name path: route through Rejection.unknownTable so the candidate hint
        // surfaces a typo fix.
        assertThat(rejection).isInstanceOf(no.sikt.graphitron.rewrite.model.Rejection.AuthorError.UnknownName.class);
        assertThat(rejection.message())
            .startsWith("table 'nonexistent' could not be resolved in the jOOQ catalog");
    }

    @Test
    void unknownTableRejection_qualifiedMiss_fallsThroughToUnknownTable() {
        var ctx = new BuildContext(null, multi(), null);
        // Qualified name where the schema is unknown; never matches findCandidateSchemasFor
        // (which compares against bare table names), so the ambiguity branch is skipped.
        var rejection = ctx.unknownTableRejection("nonexistent.widget");
        assertThat(rejection).isInstanceOf(no.sikt.graphitron.rewrite.model.Rejection.AuthorError.UnknownName.class);
        assertThat(rejection.message())
            .startsWith("table 'nonexistent.widget' could not be resolved in the jOOQ catalog");
    }
}
