package no.sikt.graphitron.rewrite;

import no.sikt.graphitron.javapoet.ClassName;
import no.sikt.graphitron.rewrite.test.tier.UnitTier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * R78 phase 2a coverage for {@link JooqCatalog}'s strict-resolution multi-schema API,
 * lifted by R81 onto the {@link JooqCatalog.TableResolution} sealed sub-taxonomy:
 * {@link JooqCatalog#parseQualifiedTableName}, {@link JooqCatalog#findTable(String)},
 * {@link JooqCatalog#findTable(String, String)},
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

    // ---- findTable(String) — sealed TableResolution ----

    private static JooqCatalog.TableEntry resolved(JooqCatalog.TableResolution r) {
        return ((JooqCatalog.TableResolution.Resolved) r).entry();
    }

    @Test
    void findTable_unqualifiedUnique_isResolved() {
        var found = multi().findTable("widget");
        assertThat(found).isInstanceOf(JooqCatalog.TableResolution.Resolved.class);
        var entry = resolved(found);
        assertThat(entry.table().getSchema().getName()).isEqualTo("multischema_a");
        assertThat(entry.table().getName()).isEqualTo("widget");
    }

    @Test
    void findTable_unqualifiedUnique_caseInsensitive() {
        assertThat(multi().findTable("WIDGET"))
            .isInstanceOf(JooqCatalog.TableResolution.Resolved.class);
        assertThat(multi().findTable("Widget"))
            .isInstanceOf(JooqCatalog.TableResolution.Resolved.class);
    }

    @Test
    void findTable_unqualifiedAmbiguous_isAmbiguousNamingBothSchemas() {
        // 'event' is defined in both multischema_a and multischema_b — the spec's
        // central R81 case: callers see Ambiguous(["multischema_a", "multischema_b"]) so the
        // diagnostic builder can suggest qualified forms inline.
        var result = multi().findTable("event");
        assertThat(result).isInstanceOf(JooqCatalog.TableResolution.Ambiguous.class);
        assertThat(((JooqCatalog.TableResolution.Ambiguous) result).schemas())
            .containsExactlyInAnyOrder("multischema_a", "multischema_b");
    }

    @Test
    void findTable_unqualifiedMissing_isNotInCatalog() {
        assertThat(multi().findTable("nonexistent_table"))
            .isInstanceOf(JooqCatalog.TableResolution.NotInCatalog.class);
    }

    @Test
    void findTable_qualified_resolvesToNamedSchema() {
        var aEvent = multi().findTable("multischema_a.event");
        var bEvent = multi().findTable("multischema_b.event");
        assertThat(aEvent).isInstanceOf(JooqCatalog.TableResolution.Resolved.class);
        assertThat(bEvent).isInstanceOf(JooqCatalog.TableResolution.Resolved.class);
        assertThat(resolved(aEvent).table().getSchema().getName()).isEqualTo("multischema_a");
        assertThat(resolved(bEvent).table().getSchema().getName()).isEqualTo("multischema_b");
        // The two qualified resolutions yield distinct tables.
        assertThat(resolved(aEvent).table().getRecordType())
            .isNotEqualTo(resolved(bEvent).table().getRecordType());
    }

    @Test
    void findTable_qualifiedSchemaUnknown_isNotInCatalog() {
        assertThat(multi().findTable("nonexistent.widget"))
            .isInstanceOf(JooqCatalog.TableResolution.NotInCatalog.class);
    }

    @Test
    void findTable_qualifiedTableMissingFromSchema_isNotInCatalog() {
        // 'gadget' lives in multischema_b only.
        assertThat(multi().findTable("multischema_a.gadget"))
            .isInstanceOf(JooqCatalog.TableResolution.NotInCatalog.class);
    }

    @Test
    void findTable_qualifiedCaseInsensitiveBothHalves() {
        assertThat(multi().findTable("MULTISCHEMA_A.WIDGET"))
            .isInstanceOf(JooqCatalog.TableResolution.Resolved.class);
        assertThat(multi().findTable("MultiSchema_B.Event"))
            .isInstanceOf(JooqCatalog.TableResolution.Resolved.class);
    }

    // ---- findTable(schema, table) — two-arg, still Optional<TableEntry> ----

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

    // ---- TableResolution.asEntry() projection ----

    @Test
    void asEntry_resolvedProjectsToOptionalOfEntry() {
        assertThat(multi().findTable("widget").asEntry()).isPresent();
    }

    @Test
    void asEntry_ambiguousProjectsToEmpty() {
        assertThat(multi().findTable("event").asEntry()).isEmpty();
    }

    @Test
    void asEntry_notInCatalogProjectsToEmpty() {
        assertThat(multi().findTable("nonexistent").asEntry()).isEmpty();
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
        var resolution = multi().findForeignKeyByName("gadget_widget_id_fkey");
        assertThat(resolution).isInstanceOf(JooqCatalog.ForeignKeyResolution.Resolved.class);
        var ref = ((JooqCatalog.ForeignKeyResolution.Resolved) resolution).ref();
        assertThat(ref.keysClass()).isEqualTo(ClassName.get(
            "no.sikt.graphitron.rewrite.multischemafixture.multischema_b", "Keys"));
        assertThat(ref.sqlName()).isEqualToIgnoringCase("gadget_widget_id_fkey");
        // Stock JavaGenerator names the constant <TABLE>__<FK_NAME> (uppercased), not FK_<...>;
        // pin the upper-cased SQL constraint name as the suffix to avoid coupling to the table prefix.
        assertThat(ref.constantName()).endsWith("GADGET_WIDGET_ID_FKEY");
    }

    @Test
    void findForeignKeyByName_unknownConstraint_isNotInCatalog() {
        assertThat(multi().findForeignKeyByName("not_a_fk"))
            .isInstanceOf(JooqCatalog.ForeignKeyResolution.NotInCatalog.class);
    }

    @Test
    void foreignKeyResolution_asRef_projectsResolvedToOptional() {
        var resolved = multi().findForeignKeyByName("gadget_widget_id_fkey");
        assertThat(resolved.asRef()).isPresent();
    }

    @Test
    void foreignKeyResolution_asRef_projectsNotInCatalogToEmpty() {
        var missing = multi().findForeignKeyByName("not_a_fk");
        assertThat(missing.asRef()).isEmpty();
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
        // Qualified name where the schema is unknown; the qualified form parses to a two-arg
        // findTable miss, so the ambiguity branch is skipped (qualified lookups never produce
        // Ambiguous by construction).
        var rejection = ctx.unknownTableRejection("nonexistent.widget");
        assertThat(rejection).isInstanceOf(no.sikt.graphitron.rewrite.model.Rejection.AuthorError.UnknownName.class);
        assertThat(rejection.message())
            .startsWith("table 'nonexistent.widget' could not be resolved in the jOOQ catalog");
    }

    // ---- BuildContext.unknownForeignKeyRejection: FK-name miss diagnostic ----

    @Test
    void unknownForeignKeyRejection_namesMissingFkAndCarriesFkAttemptKind() {
        var ctx = new BuildContext(null, multi(), null);
        var rejection = ctx.unknownForeignKeyRejection("not_a_fk");
        // UnknownName variant routes Levenshtein candidates over the catalog's FK names; the
        // attempt kind tags the rejection so LSP fix-its can scope candidate sets.
        assertThat(rejection).isInstanceOf(no.sikt.graphitron.rewrite.model.Rejection.AuthorError.UnknownName.class);
        assertThat(((no.sikt.graphitron.rewrite.model.Rejection.AuthorError.UnknownName) rejection).attemptKind())
            .isEqualTo(no.sikt.graphitron.rewrite.model.Rejection.AttemptKind.FOREIGN_KEY);
        assertThat(rejection.message())
            .startsWith("foreign key 'not_a_fk' could not be resolved in the jOOQ catalog");
    }

    // ---- Catalog-construction precondition ----

    @Test
    void verifyTablesClassPresent_missingPackageThrowsWithSchemaName() {
        // The static helper is the unit-testable shape of the precondition the constructor
        // applies to every schema in the live jOOQ catalog. A real catalog with a missing
        // Tables class is hard to forge without bespoke codegen, so the helper takes the
        // (schemaName, packageName) pair the constructor would extract.
        org.assertj.core.api.Assertions.assertThatThrownBy(
                () -> JooqCatalog.verifyTablesClassPresent("synthetic_schema", "no.such.package"))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("synthetic_schema")
            .hasMessageContaining("<tables>true</tables>")
            .hasMessageContaining("no.such.package.Tables");
    }

    @Test
    void verifyTablesClassPresent_existingPackageAcceptsSilently() {
        // Real fixture package: this should not throw.
        JooqCatalog.verifyTablesClassPresent("multischema_a",
            "no.sikt.graphitron.rewrite.multischemafixture.multischema_a");
    }

    @Test
    void constructor_realCatalogWithAllTablesClassesSucceeds() {
        // Sanity check: the multischema fixture has Tables in every schema, so the constructor's
        // precondition pass over schemaStream() does not throw.
        new JooqCatalog(MULTI_PACKAGE);
    }

    // ---- synthesizeFkJoin: FkJoinResolution sub-taxonomy ----

    @Test
    void synthesizeFkJoin_resolvedHappyPath() {
        var ctx = new BuildContext(null, multi(), null);
        var fk = multi().findForeignKey("gadget_widget_id_fkey").orElseThrow();
        var result = ctx.synthesizeFkJoin(fk, "gadget", "fieldName", 0, null, /*selfRefFkOnSource=*/false);
        assertThat(result).isInstanceOf(BuildContext.FkJoinResolution.Resolved.class);
        var resolved = ((BuildContext.FkJoinResolution.Resolved) result).fkJoin();
        assertThat(resolved.fk().sqlName()).isEqualToIgnoringCase("gadget_widget_id_fkey");
        // Non-null fk is enforced by the FkJoin canonical constructor; existence of the
        // Resolved variant proves the type-encoded guarantee here.
        assertThat(resolved.fk()).isNotNull();
    }

    @Test
    void synthesizeFkJoin_unknownEndpointTableSurfacesUnknownTable() {
        // Force the source-side endpoint to a fabricated name. The FK still resolves, but the
        // origin findTable call hits NotInCatalog, so synthesizeFkJoin propagates UnknownTable
        // carrying the failing TableResolution variant.
        var ctx = new BuildContext(null, multi(), null);
        var fk = multi().findForeignKey("gadget_widget_id_fkey").orElseThrow();
        var result = ctx.synthesizeFkJoin(fk, "fabricated_source", "fieldName", 0, null, /*selfRefFkOnSource=*/false);
        assertThat(result).isInstanceOf(BuildContext.FkJoinResolution.UnknownTable.class);
        var u = (BuildContext.FkJoinResolution.UnknownTable) result;
        // Either endpoint can fail first; the requested name on the carry slot is the one
        // synthesizeFkJoin tried to look up and could not resolve.
        assertThat(u.failure()).isInstanceOf(JooqCatalog.TableResolution.NotInCatalog.class);
    }

    @Test
    void fkJoinResolution_unknownForeignKey_carriesFkNameAndProjectsToEmpty() {
        // The {@link BuildContext.FkJoinResolution.UnknownForeignKey} arm covers the structural
        // case where {@code findForeignKeyByName} returns {@code NotInCatalog} despite the input
        // {@link org.jooq.ForeignKey} being non-null — defensive against catalog-vs-FK mismatch.
        // Current production callers (parsePath / parsePathElement / NodeIdLeafResolver / the
        // IdReference shim) all pre-resolve the FK via {@link JooqCatalog#findForeignKey}, so the
        // arm is unreachable from the existing call graph; the taxonomy still expresses the
        // structural completeness so future call sites must handle the shape.
        var resolution = new BuildContext.FkJoinResolution.UnknownForeignKey("fabricated_fk");
        assertThat(resolution.fkName()).isEqualTo("fabricated_fk");
        assertThat(resolution.asFkJoin()).isEmpty();
    }

    @Test
    void fkJoinResolution_resolved_projectsToOptionalOfFkJoin() {
        var ctx = new BuildContext(null, multi(), null);
        var fk = multi().findForeignKey("gadget_widget_id_fkey").orElseThrow();
        var result = ctx.synthesizeFkJoin(fk, "gadget", "fieldName", 0, null, /*selfRefFkOnSource=*/false);
        assertThat(result.asFkJoin()).isPresent();
    }

    @Test
    void fkJoinResolution_unknownTable_projectsToEmpty() {
        var ctx = new BuildContext(null, multi(), null);
        var fk = multi().findForeignKey("gadget_widget_id_fkey").orElseThrow();
        var result = ctx.synthesizeFkJoin(fk, "fabricated_source", "fieldName", 0, null, /*selfRefFkOnSource=*/false);
        assertThat(result.asFkJoin()).isEmpty();
    }
}
