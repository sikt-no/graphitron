package no.sikt.graphitron.rewrite;

import no.sikt.graphitron.javapoet.ClassName;
import no.sikt.graphitron.rewrite.model.On;
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

    /**
     * Fetches a raw jOOQ {@link org.jooq.ForeignKey} for tests that drive the FK-object APIs.
     * Uses the R440 scoped lookup with a {@code null} scope; every name passed here is unique
     * across the fixture's schemas, so the lookup always {@link JooqCatalog.ForeignKeyLookup.Resolved}.
     */
    private static org.jooq.ForeignKey<?, ?> fkByName(String name) {
        return ((JooqCatalog.ForeignKeyLookup.Resolved) multi().findForeignKey(name, null)).fk();
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
        var fk = fkByName("gadget_widget_id_fkey");
        assertThat(fk.getTable().getSchema().getName()).isEqualTo("multischema_b");
        assertThat(fk.getKey().getTable().getSchema().getName()).isEqualTo("multischema_a");
    }

    // ---- findForeignKeyRef: R440 identity-based typed FK reference ----

    @Test
    void findForeignKeyRef_returnsSchemaSegmentedKeysClass() {
        // The cross-schema FK is declared on multischema_b's Keys class — this is the
        // multi-schema bug R78 fixes: a per-emit-site `ClassName.get(jooqPackage, "Keys")`
        // with jooqPackage = root would compile to the non-existent root.Keys (no Keys
        // class in the root package under multi-schema codegen). The identity lookup picks
        // the FK-holder schema's Keys class off the FK's own schema.
        var resolution = multi().findForeignKeyRef(fkByName("gadget_widget_id_fkey"));
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
    void findForeignKeyRef_foreignInstance_isNotInCatalog() {
        // A real FK object resolved against a catalog with no jOOQ package present: the FK's
        // holder-schema Keys scan cannot run (catalog absent), so the defensive NotInCatalog arm
        // fires. This pins the catalog-vs-FK-mismatch shape the identity lookup guards against.
        var fk = fkByName("gadget_widget_id_fkey");
        var emptyCatalog = new JooqCatalog("no.sikt.graphitron.rewrite.absentfixture");
        assertThat(emptyCatalog.findForeignKeyRef(fk))
            .isInstanceOf(JooqCatalog.ForeignKeyResolution.NotInCatalog.class);
    }

    @Test
    void foreignKeyResolution_asRef_projectsResolvedToOptional() {
        var resolved = multi().findForeignKeyRef(fkByName("gadget_widget_id_fkey"));
        assertThat(resolved.asRef()).isPresent();
    }

    @Test
    void foreignKeyResolution_asRef_projectsNotInCatalogToEmpty() {
        var fk = fkByName("gadget_widget_id_fkey");
        var missing = new JooqCatalog("no.sikt.graphitron.rewrite.absentfixture").findForeignKeyRef(fk);
        assertThat(missing.asRef()).isEmpty();
    }

    // ---- BuildContext.unknownTableRejection: ambiguity-aware rejection wrap ----

    @Test
    void unknownTableRejection_unqualifiedAmbiguous_namesSchemasAndQualifiedForms() {
        var ctx = new BuildContext(null, multi(), stubRewriteContext());
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
        var ctx = new BuildContext(null, multi(), stubRewriteContext());
        var rejection = ctx.unknownTableRejection("nonexistent");
        // Missing-name path: route through Rejection.unknownTable so the candidate hint
        // surfaces a typo fix.
        assertThat(rejection).isInstanceOf(no.sikt.graphitron.rewrite.model.Rejection.AuthorError.UnknownName.class);
        assertThat(rejection.message())
            .startsWith("table 'nonexistent' could not be resolved in the jOOQ catalog");
    }

    @Test
    void unknownTableRejection_qualifiedMiss_fallsThroughToUnknownTable() {
        var ctx = new BuildContext(null, multi(), stubRewriteContext());
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
        var ctx = new BuildContext(null, multi(), stubRewriteContext());
        var rejection = ctx.unknownForeignKeyRejection("not_a_fk");
        // UnknownName variant routes Levenshtein candidates over the catalog's FK names; the
        // attempt kind tags the rejection so LSP fix-its can scope candidate sets.
        assertThat(rejection).isInstanceOf(no.sikt.graphitron.rewrite.model.Rejection.AuthorError.UnknownName.class);
        assertThat(((no.sikt.graphitron.rewrite.model.Rejection.AuthorError.UnknownName) rejection).attemptKind())
            .isEqualTo(no.sikt.graphitron.rewrite.model.Rejection.AttemptKind.FOREIGN_KEY);
        assertThat(rejection.message())
            .startsWith("foreign key 'not_a_fk' could not be resolved in the jOOQ catalog");
    }

    @Test
    void unknownForeignKeyRejection_mirrorsAuthorFkNamespace_inCandidateHint() {
        // R259: the candidate hint must read in the same FK namespace the author typed, since
        // findForeignKey resolves both the SQL constraint name and the jOOQ Java-constant
        // (TABLE__CONSTRAINT) name. The nodeid fixture carries a real FK whose constant name holds
        // the `__` separator and whose SQL name does not, so the two namespaces are distinguishable
        // in the (unranked) candidate list the rejection carries.
        var ctx = new BuildContext(null,
            new JooqCatalog("no.sikt.graphitron.rewrite.nodeidfixture"), stubRewriteContext());

        // Bare SQL-form attempt -> SQL-namespace candidates (no TABLE__CONSTRAINT separator).
        var sqlForm = (no.sikt.graphitron.rewrite.model.Rejection.AuthorError.UnknownName)
            ctx.unknownForeignKeyRejection("reordered_fk_child_parent_fkez");
        assertThat(sqlForm.candidates())
            .as("bare SQL-form attempt gets SQL-namespace candidates")
            .isNotEmpty()
            .noneMatch(c -> c.contains("__"));

        // jOOQ-constant-form attempt (contains `__`) -> Java-constant-namespace candidates, which
        // carry the `__` separator. Fails under the pre-R259 behaviour (candidates were always the
        // SQL names regardless of the namespace the author typed).
        var constForm = (no.sikt.graphitron.rewrite.model.Rejection.AuthorError.UnknownName)
            ctx.unknownForeignKeyRejection("reordered_fk_child__bogus_fk");
        assertThat(constForm.candidates())
            .as("__-form attempt gets jOOQ-constant-namespace candidates")
            .isNotEmpty()
            .anyMatch(c -> c.contains("__"));
    }

    // ---- Catalog-construction precondition ----

    @Test
    void verifyTablesClassPresent_missingPackageThrowsWithSchemaName() {
        // The static helper is the unit-testable shape of the precondition the constructor
        // applies to every schema in the live jOOQ catalog. A real catalog with a missing
        // Tables class is hard to forge without bespoke codegen, so the helper takes the
        // (schemaName, packageName) pair the constructor would extract.
        org.assertj.core.api.Assertions.assertThatThrownBy(
                () -> JooqCatalog.verifyTablesClassPresent("synthetic_schema", "no.such.package",
                    JooqCatalogMultiSchemaTest.class.getClassLoader()))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("synthetic_schema")
            .hasMessageContaining("<tables>true</tables>")
            .hasMessageContaining("no.such.package.Tables");
    }

    @Test
    void verifyTablesClassPresent_existingPackageAcceptsSilently() {
        // Real fixture package: this should not throw.
        JooqCatalog.verifyTablesClassPresent("multischema_a",
            "no.sikt.graphitron.rewrite.multischemafixture.multischema_a",
            JooqCatalogMultiSchemaTest.class.getClassLoader());
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
        var ctx = new BuildContext(null, multi(), stubRewriteContext());
        var fk = fkByName("gadget_widget_id_fkey");
        var result = ctx.synthesizeFkJoin(fk, "gadget", "fieldName", 0, null, /*selfRefFkOnSource=*/false);
        assertThat(result).isInstanceOf(BuildContext.FkJoinResolution.Resolved.class);
        var resolved = ((BuildContext.FkJoinResolution.Resolved) result).hop();
        var pairs = (On.ColumnPairs) resolved.on();
        assertThat(TestFixtures.fkRef(pairs).sqlName()).isEqualToIgnoringCase("gadget_widget_id_fkey");
        // Non-null fk is enforced by the On.Keying.ForeignKey canonical constructor; existence of
        // the Resolved variant proves the type-encoded guarantee here.
        assertThat(TestFixtures.fkRef(pairs)).isNotNull();
    }

    @Test
    void synthesizeFkJoin_fabricatedSourceStillResolvesByClass() {
        // R440: the fabricated-source UnknownTable case is retired. Both endpoints are resolved by
        // jOOQ class identity off the FK object, so a source SQL name that does not match any
        // catalog table no longer breaks synthesis — the FK pins the exact origin class regardless.
        // (Author-facing source-membership is enforced upstream by the {key:} touches-check, pinned
        // by parsePathElement_keyNotTouchingSource_* below, not by this defensive arm.)
        var ctx = new BuildContext(null, multi(), stubRewriteContext());
        var fk = fkByName("gadget_widget_id_fkey");
        var result = ctx.synthesizeFkJoin(fk, "fabricated_source", "fieldName", 0, null, /*selfRefFkOnSource=*/false);
        assertThat(result).isInstanceOf(BuildContext.FkJoinResolution.Resolved.class);
    }

    @Test
    void fkJoinResolution_unknownForeignKey_carriesFkNameAndProjectsToEmpty() {
        // The {@link BuildContext.FkJoinResolution.UnknownForeignKey} arm covers the structural
        // case where {@code findForeignKeyRef} returns {@code NotInCatalog} despite the input
        // {@link org.jooq.ForeignKey} being non-null — defensive against catalog-vs-FK mismatch.
        // After R440 every production caller resolves the FK by identity before reaching
        // synthesizeFkJoin, so the arm is unreachable from the existing call graph; the taxonomy
        // still expresses the structural completeness so future call sites must handle the shape.
        var resolution = new BuildContext.FkJoinResolution.UnknownForeignKey("fabricated_fk");
        assertThat(resolution.fkName()).isEqualTo("fabricated_fk");
        assertThat(resolution.asHop()).isEmpty();
    }

    @Test
    void fkJoinResolution_resolved_projectsToOptionalOfHop() {
        var ctx = new BuildContext(null, multi(), stubRewriteContext());
        var fk = fkByName("gadget_widget_id_fkey");
        var result = ctx.synthesizeFkJoin(fk, "gadget", "fieldName", 0, null, /*selfRefFkOnSource=*/false);
        assertThat(result.asHop()).isPresent();
    }

    @Test
    void fkJoinResolution_unknownTable_projectsToEmpty() {
        // R440: the defensive UnknownTable arm can no longer be provoked through synthesizeFkJoin
        // (both endpoints resolve by class), so the projection is pinned on a directly-constructed
        // instance — the same shape as fkJoinResolution_unknownForeignKey above.
        var resolution = new BuildContext.FkJoinResolution.UnknownTable(
            "fabricated", new JooqCatalog.TableResolution.NotInCatalog());
        assertThat(resolution.asHop()).isEmpty();
    }

    // ---- R396: FK source-side identity primitives (schema-qualified / case-mismatched @table) ----
    //
    // The signal→widget FK (signal_widget_id_fkey) lives entirely in multischema_a. jOOQ renders
    // both endpoint names unqualified ("signal", "widget"), so a schema-qualified or case-mismatched
    // @table echo ("multischema_a.signal", "multischema_a.SIGNAL") missed the pre-R396 bare
    // equalsIgnoreCase compare. Class identity, resolved through findTable, restores the match.

    @Test
    void foreignKeyTouchesTable_qualifiedSourceOnFkChildSide_isTrue() {
        var fk = fkByName("signal_widget_id_fkey");
        assertThat(multi().foreignKeyTouchesTable(fk, "multischema_a.signal")).isTrue();
    }

    @Test
    void foreignKeyTouchesTable_qualifiedUpperCaseSource_isTrue() {
        // The schema-qualified + upper-case form the R395 execution fixture is tightened to.
        var fk = fkByName("signal_widget_id_fkey");
        assertThat(multi().foreignKeyTouchesTable(fk, "multischema_a.SIGNAL")).isTrue();
    }

    @Test
    void foreignKeyTouchesTable_qualifiedSourceOnReferencedSide_isTrue() {
        // widget is the referenced (key) endpoint — touches, but is not the source side.
        var fk = fkByName("signal_widget_id_fkey");
        assertThat(multi().foreignKeyTouchesTable(fk, "multischema_a.widget")).isTrue();
    }

    @Test
    void foreignKeyTouchesTable_nonEndpointTable_isFalse() {
        // gadget is neither endpoint of the signal→widget FK.
        var fk = fkByName("signal_widget_id_fkey");
        assertThat(multi().foreignKeyTouchesTable(fk, "gadget")).isFalse();
    }

    @Test
    void foreignKeyTouchesTable_crossSchemaSameName_distinguishesByIdentity() {
        // 'event' collides across schemas; identity (not bare name) picks the right one. The
        // signal→widget FK touches neither event, so both qualified forms resolve to false.
        var fk = fkByName("signal_widget_id_fkey");
        assertThat(multi().foreignKeyTouchesTable(fk, "multischema_a.event")).isFalse();
        assertThat(multi().foreignKeyTouchesTable(fk, "multischema_b.event")).isFalse();
    }

    @Test
    void foreignKeyOnSource_qualifiedSourceIsFkChildSide_isTrue() {
        var fk = fkByName("signal_widget_id_fkey");
        assertThat(multi().foreignKeyOnSource(fk, "multischema_a.signal", /*selfRefHint=*/false)).isTrue();
        assertThat(multi().foreignKeyOnSource(fk, "multischema_a.SIGNAL", /*selfRefHint=*/false)).isTrue();
    }

    @Test
    void foreignKeyOnSource_qualifiedSourceIsReferencedSide_isFalse() {
        // From the widget (referenced) side the FK is not on the source — the orientation the
        // pre-R396 bare compare silently inverted for a schema-qualified name.
        var fk = fkByName("signal_widget_id_fkey");
        assertThat(multi().foreignKeyOnSource(fk, "multischema_a.widget", /*selfRefHint=*/false)).isFalse();
    }

    @Test
    void findForeignKeysBetweenTables_qualifiedBothArgs_returnsTheFk() {
        var fks = multi().findForeignKeysBetweenTables("multischema_a.signal", "multischema_a.widget");
        assertThat(fks).extracting(fk -> fk.getName())
            .containsExactly("signal_widget_id_fkey");
    }

    // ---- R396: synthesizeFkJoin orients correctly under a schema-qualified source @table ----

    @Test
    void synthesizeFkJoin_qualifiedSource_orientsOriginSignalTargetWidget() {
        var ctx = new BuildContext(null, multi(), stubRewriteContext());
        var fk = fkByName("signal_widget_id_fkey");
        var result = ctx.synthesizeFkJoin(fk, "multischema_a.signal", "widget", 0, null,
            /*selfRefFkOnSource=*/false);
        assertThat(result).isInstanceOf(BuildContext.FkJoinResolution.Resolved.class);
        var fkJoin = ((BuildContext.FkJoinResolution.Resolved) result).hop();

        // Origin is signal, target is widget — the pre-R396 bare compare would fail the
        // "multischema_a.signal".equalsIgnoreCase("signal") test, mis-orient the join, and
        // swap origin/target so the slot pairing inverts.
        assertThat(fkJoin.originTable().tableClass())
            .isEqualTo(ClassName.get(MULTI_PACKAGE + ".multischema_a.tables", "Signal"));
        assertThat(fkJoin.targetTable().tableClass())
            .isEqualTo(ClassName.get(MULTI_PACKAGE + ".multischema_a.tables", "Widget"));
        // Slot orientation: source column sits on signal (widget_id), target on widget (widget_id).
        var pairs = (On.ColumnPairs) fkJoin.on();
        assertThat(pairs.sourceSideColumns()).extracting(c -> c.sqlName()).containsExactly("widget_id");
        assertThat(pairs.targetSideColumns()).extracting(c -> c.sqlName()).containsExactly("widget_id");
    }

    // ---- R440: FK-join endpoint + FK resolution by class / reference identity ----
    //
    // The 'note' table exists in BOTH schemas, each carrying an FK explicitly named
    // 'note_event_fk' into its OWN schema's 'event'. That yields the two collisions the earlier
    // fixture lacked: a colliding bare TARGET table name ('event', reached via FK synthesis) and a
    // colliding FK CONSTRAINT name ('note_event_fk') across schemas.

    // D1: findForeignKeyRef resolves each schema's colliding-name FK to its own Keys class.

    @Test
    void findForeignKeyRef_collidingConstraintName_resolvesPerSchemaKeysClass() {
        var fkA = ((JooqCatalog.ForeignKeyLookup.Resolved)
            multi().findForeignKey("note_event_fk", "multischema_a.note")).fk();
        var fkB = ((JooqCatalog.ForeignKeyLookup.Resolved)
            multi().findForeignKey("note_event_fk", "multischema_b.note")).fk();

        var refA = ((JooqCatalog.ForeignKeyResolution.Resolved) multi().findForeignKeyRef(fkA)).ref();
        var refB = ((JooqCatalog.ForeignKeyResolution.Resolved) multi().findForeignKeyRef(fkB)).ref();
        assertThat(refA.keysClass()).isEqualTo(ClassName.get(
            "no.sikt.graphitron.rewrite.multischemafixture.multischema_a", "Keys"));
        assertThat(refB.keysClass()).isEqualTo(ClassName.get(
            "no.sikt.graphitron.rewrite.multischemafixture.multischema_b", "Keys"));
    }

    // D2: synthesizeFkJoin over the colliding-name FK resolves the endpoint 'event' by class, from
    // each side, where the pre-R440 bare-name target lookup returned Ambiguous and the join failed.

    @Test
    void synthesizeFkJoin_collidingTargetName_resolvesSchemaAEventByClass() {
        var ctx = new BuildContext(null, multi(), stubRewriteContext());
        var fkA = ((JooqCatalog.ForeignKeyLookup.Resolved)
            multi().findForeignKey("note_event_fk", "multischema_a.note")).fk();
        var result = ctx.synthesizeFkJoin(fkA, "multischema_a.note", "event", 0, null,
            /*selfRefFkOnSource=*/false);
        assertThat(result).isInstanceOf(BuildContext.FkJoinResolution.Resolved.class);
        var hop = ((BuildContext.FkJoinResolution.Resolved) result).hop();
        assertThat(hop.targetTable().tableClass())
            .isEqualTo(ClassName.get(MULTI_PACKAGE + ".multischema_a.tables", "Event"));
        assertThat(hop.originTable().tableClass())
            .isEqualTo(ClassName.get(MULTI_PACKAGE + ".multischema_a.tables", "Note"));
    }

    @Test
    void synthesizeFkJoin_collidingTargetName_resolvesSchemaBEventByClass() {
        var ctx = new BuildContext(null, multi(), stubRewriteContext());
        var fkB = ((JooqCatalog.ForeignKeyLookup.Resolved)
            multi().findForeignKey("note_event_fk", "multischema_b.note")).fk();
        var result = ctx.synthesizeFkJoin(fkB, "multischema_b.note", "event", 0, null,
            /*selfRefFkOnSource=*/false);
        assertThat(result).isInstanceOf(BuildContext.FkJoinResolution.Resolved.class);
        var hop = ((BuildContext.FkJoinResolution.Resolved) result).hop();
        assertThat(hop.targetTable().tableClass())
            .isEqualTo(ClassName.get(MULTI_PACKAGE + ".multischema_b.tables", "Event"));
        assertThat(hop.originTable().tableClass())
            .isEqualTo(ClassName.get(MULTI_PACKAGE + ".multischema_b.tables", "Note"));
    }

    // D3: scoped name lookup disambiguates the colliding constraint name by source, and surfaces
    // Ambiguous (with the colliding schemas) when the scope cannot resolve it.

    @Test
    void findForeignKey_scopedBySource_resolvesTheOwningSchemasFk() {
        var lookupA = multi().findForeignKey("note_event_fk", "multischema_a.note");
        assertThat(lookupA).isInstanceOf(JooqCatalog.ForeignKeyLookup.Resolved.class);
        var fkA = ((JooqCatalog.ForeignKeyLookup.Resolved) lookupA).fk();
        assertThat(fkA.getTable().getSchema().getName()).isEqualTo("multischema_a");

        var lookupB = multi().findForeignKey("note_event_fk", "multischema_b.note");
        assertThat(lookupB).isInstanceOf(JooqCatalog.ForeignKeyLookup.Resolved.class);
        var fkB = ((JooqCatalog.ForeignKeyLookup.Resolved) lookupB).fk();
        assertThat(fkB.getTable().getSchema().getName()).isEqualTo("multischema_b");
    }

    @Test
    void findForeignKey_nullScopeCollidingName_isAmbiguousNamingBothSchemas() {
        var lookup = multi().findForeignKey("note_event_fk", null);
        assertThat(lookup).isInstanceOf(JooqCatalog.ForeignKeyLookup.Ambiguous.class);
        assertThat(((JooqCatalog.ForeignKeyLookup.Ambiguous) lookup).schemas())
            .containsExactlyInAnyOrder("multischema_a", "multischema_b");
    }

    @Test
    void findForeignKey_unknownName_isNotInCatalog() {
        assertThat(multi().findForeignKey("not_a_fk", null))
            .isInstanceOf(JooqCatalog.ForeignKeyLookup.NotInCatalog.class);
    }

    @Test
    void ambiguousForeignKeyRejection_producesStructuralProseWithSchemasAndQualifiedForms() {
        var ctx = new BuildContext(null, multi(), stubRewriteContext());
        var rejection = ctx.ambiguousForeignKeyRejection(
            "note_event_fk", java.util.List.of("multischema_a", "multischema_b"));
        assertThat(rejection).isInstanceOf(no.sikt.graphitron.rewrite.model.Rejection.AuthorError.Structural.class);
        assertThat(rejection.message())
            .contains("foreign key 'note_event_fk' is ambiguous")
            .contains("multischema_a")
            .contains("multischema_b")
            .contains("'multischema_a.note_event_fk'")
            .contains("'multischema_b.note_event_fk'");
    }

    // D2 membership enforcer: the {key:} path rejects an FK that does not touch the current source.

    @Test
    void parsePathElement_keyNotTouchingSource_rejectsBeforeSynthesis() {
        var ctx = new BuildContext(null, multi(), stubRewriteContext());
        // signal_widget_id_fkey touches signal/widget, not gadget; standing on gadget the {key:}
        // membership check (foreignKeyTouchesTable) rejects before synthesizeFkJoin is entered.
        var parsed = ctx.parsePath(
            fieldWithKeyReference("signal_widget_id_fkey"), "f", "gadget", "widget");
        assertThat(parsed.errorMessage())
            .contains("signal_widget_id_fkey")
            .contains("does not connect to table 'gadget'");
    }

    /** A field carrying {@code @reference(path: [{key: fkName}])} for driving {@code parsePath}. */
    private static graphql.schema.GraphQLFieldDefinition fieldWithKeyReference(String fkName) {
        var keyField = graphql.schema.GraphQLInputObjectField.newInputObjectField()
            .name("key").type(graphql.Scalars.GraphQLString).build();
        var elementType = graphql.schema.GraphQLInputObjectType.newInputObject()
            .name("ReferencePathElementStub").field(keyField).build();
        var pathArg = graphql.schema.GraphQLAppliedDirectiveArgument.newArgument()
            .name("path")
            .type(graphql.schema.GraphQLList.list(elementType))
            .valueProgrammatic(java.util.List.of(java.util.Map.of("key", fkName)))
            .build();
        var refDir = graphql.schema.GraphQLAppliedDirective.newDirective()
            .name("reference")
            .argument(pathArg)
            .build();
        return graphql.schema.GraphQLFieldDefinition.newFieldDefinition()
            .name("f")
            .type(graphql.Scalars.GraphQLString)
            .withAppliedDirective(refDir)
            .build();
    }

    private static RewriteContext stubRewriteContext() {
        return new RewriteContext(
            java.util.List.of(),
            java.nio.file.Path.of("."),
            java.nio.file.Path.of("."),
            "unused",
            "unused",
            java.util.Map.of());
    }
}
