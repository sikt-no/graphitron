package no.sikt.graphitron.rewrite;

import no.sikt.graphitron.javapoet.TypeSpec;
import no.sikt.graphitron.rewrite.generators.TypeClassGenerator;
import no.sikt.graphitron.rewrite.generators.util.TypeSpecAssertions;
import org.junit.jupiter.api.Test;

import static no.sikt.graphitron.common.configuration.TestConfiguration.DEFAULT_OUTPUT_PACKAGE;
import static org.assertj.core.api.Assertions.assertThat;
import no.sikt.graphitron.rewrite.test.tier.PipelineTier;

/**
 * SDL → classified schema → generated {@code TypeSpec} pipeline tests pinning that a
 * DataLoader-backed {@code @service} child's {@code SourceKey} columns are force-included in the
 * parent type's {@code $fields} projection.
 *
 * <p>The {@code @service} DataLoader shapes ({@link no.sikt.graphitron.rewrite.model.ChildField.ServiceTableField},
 * {@link no.sikt.graphitron.rewrite.model.ChildField.ServiceRecordField}) build their DataLoader
 * key off the parent source record, so the parent SELECT must project the key columns even when
 * the client's selection contains no field mapping to them — otherwise the key extraction reads
 * {@code null} and the child silently resolves to {@code null} (the federation
 * {@code _entities}-fetch shape). Split-{@code @reference} children get the same treatment via
 * the shared {@link no.sikt.graphitron.rewrite.model.BatchKeyField} arm in
 * {@code TypeClassGenerator.collectRequiredProjectionColumns}; their coverage lives in
 * {@link NestingFieldPipelineTest} and {@link TableMethodFieldPipelineTest}.
 *
 * <p>Every fixture's service method carries a Sources param ({@code Set<Row1<Integer>>}), so the
 * field classifies with a non-null {@code SourceKey} (the DataLoader-backed shape this item is
 * about; a no-Sources method is a plain per-parent delegation with no key read and no projection
 * need). Every fixture's parent type deliberately carries <em>no</em> other force-projecting
 * child ({@code @splitQuery}/{@code @tableMethod} sibling), so a regression of the
 * {@code BatchKeyField} arm turns these red rather than being masked by an unrelated sibling's
 * projection.
 *
 * <p>A further extension of the same walk: when the child's key wrap is
 * {@code SourceKey.Wrap.TableRecord} (typed-record Sources parameter), the projection
 * requirement widens from the key columns to the full parent row — see the typed-record test
 * group below and {@code TypeClassGenerator.RequiredProjection}.
 */
@PipelineTier
class ServiceProjectionPipelineTest {

    /** Table-bound {@code @service} return → {@code ServiceTableField}. */
    @Test
    void serviceTableFieldChild_parentDollarFieldsProjectsSourceKeyColumn() {
        var languageType = findType("Language", """
            type Language @table(name: "language") { languageId: Int @field(name: "language_id") }
            type Film @table(name: "film") { title: String }
            type Query { language: Language }
            extend type Language {
                films: [Film!]! @service(
                    service: {className: "no.sikt.graphitron.rewrite.generators.TestFilmService", method: "getFilmsMapped"}
                )
            }
            """);

        assertThat(TypeSpecAssertions.appendsRequiredColumn(languageType, "LANGUAGE_ID"))
            .as("parent $fields force-projects the @service child's SourceKey column (parent PK)")
            .isTrue();
    }

    /** Scalar {@code @service} return → {@code ServiceRecordField}. */
    @Test
    void serviceRecordFieldChild_parentDollarFieldsProjectsSourceKeyColumn() {
        var languageType = findType("Language", """
            type Language @table(name: "language") {
                name: String @field(name: "name")
                rank: Int @service(
                    service: {className: "no.sikt.graphitron.rewrite.generators.TestFilmService", method: "getRankMapped"}
                )
            }
            type Query { language: Language }
            """);

        assertThat(TypeSpecAssertions.appendsRequiredColumn(languageType, "LANGUAGE_ID"))
            .as("parent $fields force-projects the @service child's SourceKey column (parent PK)")
            .isTrue();
    }

    /**
     * A {@code @service} child nested under a plain-object {@code NestingField} shares the outer
     * table type's {@code $fields}; the recursion in {@code collectRequiredProjectionColumns}
     * must surface its SourceKey column into the outer parent's projection.
     */
    @Test
    void nestedServiceChild_projectsOuterParentSourceKeyColumn() {
        var languageType = findType("Language", """
            type Language @table(name: "language") { info: LanguageInfo }
            type LanguageInfo {
                rank: Int @service(
                    service: {className: "no.sikt.graphitron.rewrite.generators.TestFilmService", method: "getRankMapped"}
                )
            }
            type Query { language: Language }
            """);

        assertThat(TypeSpecAssertions.appendsRequiredColumn(languageType, "LANGUAGE_ID"))
            .as("outer parent $fields force-projects the nested @service child's SourceKey column")
            .isTrue();
    }

    // ===== typed-TableRecord source shape → full parent-row projection =====
    //
    // When the @service child's Sources parameter is a typed TableRecord (Set<LanguageRecord>),
    // the key wrap is SourceKey.Wrap.TableRecord and the key extraction is
    // env.getSource().into(Tables.LANGUAGE) — the service body may read ANY parent column off
    // the record, per the documented contract ("fully-populated parent records"). The parent
    // $fields must therefore project the whole parent row, not just the key columns.

    /** List-valued typed-record {@code @service} return → {@code ServiceTableField}. */
    @Test
    void serviceTableFieldChild_tableRecordSource_projectsFullParentRow() {
        var languageType = findType("Language", """
            type Language @table(name: "language") { languageId: Int @field(name: "language_id") }
            type Film @table(name: "film") { title: String }
            type Query { language: Language }
            extend type Language {
                films: [Film!]! @service(
                    service: {className: "no.sikt.graphitron.rewrite.generators.TestFilmService", method: "getFilmsMappedByRecord"}
                )
            }
            """);

        assertThat(TypeSpecAssertions.appendsFullParentRow(languageType))
            .as("parent $fields projects the full parent row for a TableRecord-sourced @service child")
            .isTrue();
    }

    /** Scalar typed-record {@code @service} return → {@code ServiceRecordField}. */
    @Test
    void serviceRecordFieldChild_tableRecordSource_projectsFullParentRow() {
        var languageType = findType("Language", """
            type Language @table(name: "language") {
                name: String @field(name: "name")
                rank: Int @service(
                    service: {className: "no.sikt.graphitron.rewrite.generators.TestFilmService", method: "getRankMappedByRecord"}
                )
            }
            type Query { language: Language }
            """);

        assertThat(TypeSpecAssertions.appendsFullParentRow(languageType))
            .as("parent $fields projects the full parent row for a TableRecord-sourced @service child")
            .isTrue();
    }

    /**
     * Contrast: a {@code Record1}-sourced sibling of the same shape keeps the key-columns-only
     * projection and gets no full-row append — the full-row widening is gated on the key wrap
     * ({@code SourceKey.Wrap.TableRecord}), not on the {@code @service} field variants.
     */
    @Test
    void record1SourcedServiceChild_projectsKeyColumnsOnly_noFullRowAppend() {
        var languageType = findType("Language", """
            type Language @table(name: "language") {
                name: String @field(name: "name")
                rank: Int @service(
                    service: {className: "no.sikt.graphitron.rewrite.generators.TestFilmService", method: "getRankRecordWrap"}
                )
            }
            type Query { language: Language }
            """);

        assertThat(TypeSpecAssertions.appendsRequiredColumn(languageType, "LANGUAGE_ID"))
            .as("Record1-sourced @service child still force-projects its SourceKey column")
            .isTrue();
        assertThat(TypeSpecAssertions.appendsFullParentRow(languageType))
            .as("Record1-sourced @service child must NOT widen the projection to the full row")
            .isFalse();
    }

    /**
     * A TableRecord-sourced {@code @service} child nested under a plain-object
     * {@code NestingField} shares the outer table type's {@code $fields}; the recursion in
     * {@code collectRequiredProjection} must surface the full-row requirement onto the outer
     * parent. The projected fields are the outer parent table's by construction:
     * {@code emitSelectionSwitch} threads {@code tableArg} unchanged into nested depths, so the
     * nested child's {@code into(Tables.LANGUAGE)} reads against the outer table's row.
     */
    @Test
    void nestedTableRecordServiceChild_projectsFullParentRowOnOuterParent() {
        var languageType = findType("Language", """
            type Language @table(name: "language") { info: LanguageInfo }
            type LanguageInfo {
                rank: Int @service(
                    service: {className: "no.sikt.graphitron.rewrite.generators.TestFilmService", method: "getRankMappedByRecord"}
                )
            }
            type Query { language: Language }
            """);

        assertThat(TypeSpecAssertions.appendsFullParentRow(languageType))
            .as("outer parent $fields projects the full parent row for a nested TableRecord-sourced @service child")
            .isTrue();
    }

    /**
     * Two-axis: a parent with <em>both</em> a {@code TableRecord}-wrap {@code @service} child
     * (flips the {@code reservedFullRow} axis) and a {@code Wrap.Row} {@code @splitQuery} sibling
     * (adds a base-named key column) must emit both — the reserved-aliased full row and the
     * base-named force-included column. Previously the sealed {@code FullParentRow} absorbed the
     * columns axis, so the base-named column would have been dropped; the product record keeps both.
     */
    @Test
    void tableRecordServiceChild_withSplitRowSibling_projectsReservedFullRowAndBaseKeyColumn() {
        var languageType = findType("Language", """
            type Language @table(name: "language") { languageId: Int @field(name: "language_id") }
            type Film @table(name: "film") { title: String }
            type Query { language: Language }
            extend type Language {
                filmsService: [Film!]! @service(
                    service: {className: "no.sikt.graphitron.rewrite.generators.TestFilmService", method: "getFilmsMappedByRecord"}
                )
                filmsSplit: [Film!]! @splitQuery @reference(path: [{key: "film_language_id_fkey"}]) @defaultOrder(primaryKey: true)
            }
            """);

        assertThat(TypeSpecAssertions.appendsFullParentRow(languageType))
            .as("the TableRecord-wrap @service child flips the reserved-full-row axis")
            .isTrue();
        assertThat(TypeSpecAssertions.appendsRequiredColumn(languageType, "LANGUAGE_ID"))
            .as("the Wrap.Row @splitQuery sibling still force-projects its base-named key column "
                + "(no absorption by the full-row axis)")
            .isTrue();
    }

    private static TypeSpec findType(String className, String sdl) {
        return TypeClassGenerator.generate(TestSchemaHelper.buildSchema(sdl), DEFAULT_OUTPUT_PACKAGE).stream()
            .filter(t -> t.name().equals(className))
            .findFirst()
            .orElseThrow(() -> new AssertionError("Type class not found: " + className));
    }
}
