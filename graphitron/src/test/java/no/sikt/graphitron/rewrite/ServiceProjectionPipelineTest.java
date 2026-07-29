package no.sikt.graphitron.rewrite;

import no.sikt.graphitron.javapoet.TypeSpec;
import no.sikt.graphitron.rewrite.generators.TypeFetcherGenerator;
import no.sikt.graphitron.rewrite.generators.util.TypeSpecAssertions;
import org.junit.jupiter.api.Test;

import static no.sikt.graphitron.common.configuration.TestConfiguration.DEFAULT_OUTPUT_PACKAGE;
import static org.assertj.core.api.Assertions.assertThat;
import no.sikt.graphitron.rewrite.test.tier.PipelineTier;

/**
 * SDL → classified schema → generated {@code TypeSpec} pipeline tests pinning that a
 * DataLoader-backed {@code @service} child's {@code SourceKey} columns are projected by the
 * child's own gated {@code $project} switch arm.
 *
 * <p>The {@code @service} DataLoader shapes ({@link no.sikt.graphitron.rewrite.model.ChildField.ServiceTableField},
 * {@link no.sikt.graphitron.rewrite.model.ChildField.ServiceRecordField}) build their DataLoader
 * key off the parent source record, so the parent SELECT must carry the key columns whenever the
 * child field is selected; otherwise the key extraction has no key column to read and the child
 * cannot resolve (the federation {@code _entities}-fetch shape, which is how this first
 * surfaced). The gate suffices because the fetcher only runs for a selected field, and the
 * selected field's arm is what projects the columns. Split-{@code @reference} children get the
 * same treatment via the shared correlation-key arm in {@code ProjectionCommands}; their
 * coverage lives in {@link NestingFieldPipelineTest}.
 *
 * <p>Every fixture's service method carries a Sources param ({@code Set<Row1<Integer>>}), so the
 * field classifies with a non-null {@code SourceKey} (a no-Sources method is a plain per-parent
 * delegation with no key read, no projection need and no arm).
 *
 * <p>A {@code SourceKey.Wrap.TableRecord} child (typed-record Sources parameter) demands the same
 * thing as every other wrap: its key columns, nothing wider. The typed-record group below pins
 * that non-specialness on both sides, projection and key read.
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

        assertThat(TypeSpecAssertions.armProjectsColumn(languageType, "films", "LANGUAGE_ID"))
            .as("the @service child's arm projects its SourceKey column (parent PK)")
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

        assertThat(TypeSpecAssertions.armProjectsColumn(languageType, "rank", "LANGUAGE_ID"))
            .as("the @service child's arm projects its SourceKey column (parent PK)")
            .isTrue();
    }

    /**
     * A {@code @service} child nested under a plain-object {@code NestingField} lives on the
     * nested unit, whose {@code $project} shares the anchor's table context; its gated arm
     * projects the <em>anchor</em> table's key column, landing in the anchor's list through the
     * splice.
     */
    @Test
    void nestedServiceChild_projectsOuterParentSourceKeyColumn() {
        var nestedUnit = findType("LanguageLanguageInfo", """
            type Language @table(name: "language") { info: LanguageInfo }
            type LanguageInfo {
                rank: Int @service(
                    service: {className: "no.sikt.graphitron.rewrite.generators.TestFilmService", method: "getRankMapped"}
                )
            }
            type Query { language: Language }
            """);

        assertThat(TypeSpecAssertions.armProjectsColumn(nestedUnit, "rank", "LANGUAGE_ID"))
            .as("the nested @service child's arm projects the anchor table's SourceKey column")
            .isTrue();
    }

    // ===== typed-TableRecord source shape → the same key-columns-only projection =====
    //
    // When the @service child's Sources parameter is a typed TableRecord (Set<LanguageRecord>),
    // the key wrap is SourceKey.Wrap.TableRecord. The contract with the service author is PK-only,
    // so this wrap demands exactly what every other wrap demands: its SourceKey columns, under
    // their base names. The group exists to pin that the wrap is NOT special, which is the whole
    // content of the narrowing.

    /** List-valued typed-record {@code @service} return → {@code ServiceTableField}. */
    @Test
    void serviceTableFieldChild_tableRecordSource_projectsKeyColumnsOnly() {
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

        assertThat(TypeSpecAssertions.armProjectsColumn(languageType, "films", "LANGUAGE_ID"))
            .as("the TableRecord-sourced child's arm projects its key column")
            .isTrue();
        assertThat(TypeSpecAssertions.armProjectsColumn(languageType, "films", "NAME"))
            .as("and projects no non-key column on the child's behalf")
            .isFalse();
    }

    /** Scalar typed-record {@code @service} return → {@code ServiceRecordField}. */
    @Test
    void serviceRecordFieldChild_tableRecordSource_projectsKeyColumnsOnly() {
        var languageType = findType("Language", """
            type Language @table(name: "language") {
                name: String @field(name: "name")
                rank: Int @service(
                    service: {className: "no.sikt.graphitron.rewrite.generators.TestFilmService", method: "getRankMappedByRecord"}
                )
            }
            type Query { language: Language }
            """);

        assertThat(TypeSpecAssertions.armProjectsColumn(languageType, "rank", "LANGUAGE_ID"))
            .as("the TableRecord-sourced child's arm projects its key column")
            .isTrue();
    }

    /**
     * Contrast: a {@code Record1}-sourced sibling of the same shape projects the same thing. Once
     * the wrap stops widening the projection the two are indistinguishable here, which is the
     * point; the wrap axis survives only in how the key is <em>read</em>, pinned below.
     */
    @Test
    void record1SourcedServiceChild_projectsSameKeyColumnsAsTableRecordSibling() {
        var languageType = findType("Language", """
            type Language @table(name: "language") {
                name: String @field(name: "name")
                rank: Int @service(
                    service: {className: "no.sikt.graphitron.rewrite.generators.TestFilmService", method: "getRankRecordWrap"}
                )
            }
            type Query { language: Language }
            """);

        assertThat(TypeSpecAssertions.armProjectsColumn(languageType, "rank", "LANGUAGE_ID"))
            .as("Record1-sourced @service child's arm projects its SourceKey column")
            .isTrue();
    }

    /**
     * A TableRecord-sourced {@code @service} child nested under a plain-object
     * {@code NestingField} lives on the nested unit, whose gated arm carries the key
     * requirement. The projected fields are the outer parent table's by construction: the
     * anchor's splice threads its own {@code table} argument into the nested {@code $project},
     * so the nested child's key read resolves against the outer table's row.
     */
    @Test
    void nestedTableRecordServiceChild_projectsKeyColumnOnOuterParent() {
        var nestedUnit = findType("LanguageLanguageInfo", """
            type Language @table(name: "language") { info: LanguageInfo }
            type LanguageInfo {
                rank: Int @service(
                    service: {className: "no.sikt.graphitron.rewrite.generators.TestFilmService", method: "getRankMappedByRecord"}
                )
            }
            type Query { language: Language }
            """);

        assertThat(TypeSpecAssertions.armProjectsColumn(nestedUnit, "rank", "LANGUAGE_ID"))
            .as("the nested TableRecord-sourced child's arm projects the anchor table's key column")
            .isTrue();
    }

    /**
     * A parent with both a {@code TableRecord}-wrap {@code @service} child and a {@code Wrap.Row}
     * {@code @splitQuery} sibling. Both arms project the same base-named key column (the runtime
     * accumulator dedupes when both are selected); neither widens beyond the key.
     */
    @Test
    void tableRecordServiceChild_withSplitRowSibling_projectsTheSharedKeyColumn() {
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

        assertThat(TypeSpecAssertions.armProjectsColumn(languageType, "filmsService", "LANGUAGE_ID"))
            .as("the service child's arm projects the base-named key column")
            .isTrue();
        assertThat(TypeSpecAssertions.armProjectsColumn(languageType, "filmsSplit", "LANGUAGE_ID"))
            .as("the split sibling's arm projects the same base-named key column")
            .isTrue();
        assertThat(TypeSpecAssertions.armProjectsColumn(languageType, "filmsService", "NAME"))
            .as("neither child widens the projection beyond the key")
            .isFalse();
        assertThat(TypeSpecAssertions.armProjectsColumn(languageType, "filmsSplit", "NAME"))
            .as("neither child widens the projection beyond the key")
            .isFalse();
    }

    /**
     * The consumer side of the typed-record shape: the generated {@code @service}-child fetcher's
     * key extraction is one unconditional per-key-column read, with no runtime branch on the
     * parent source's type. It can be unconditional because the key columns are present under
     * their base names on both parent arrival shapes: projected by the field's own gated arm
     * when the parent came from {@code $project} (pinned by the producer-side tests above, and
     * guaranteed at runtime because this fetcher only runs for a selected field), and carried as
     * real columns when a service hands back its own typed record. A shape assertion, not a full
     * code-string pin.
     */
    @Test
    void tableRecordServiceChild_fetcherKeyExtractionIsUnconditional() {
        var schema = TestSchemaHelper.buildSchema("""
            type Language @table(name: "language") { languageId: Int @field(name: "language_id") }
            type Film @table(name: "film") { title: String }
            type Query { language: Language }
            extend type Language {
                films: [Film!]! @service(
                    service: {className: "no.sikt.graphitron.rewrite.generators.TestFilmService", method: "getFilmsMappedByRecord"}
                )
            }
            """);

        var languageFetchers = TypeFetcherGenerator.generate(schema, DEFAULT_OUTPUT_PACKAGE).stream()
            .filter(t -> t.name().equals("LanguageFetchers"))
            .findFirst()
            .orElseThrow(() -> new AssertionError("LanguageFetchers not generated"));

        assertThat(TypeSpecAssertions.serviceChildKeyExtractionIsUnconditional(languageFetchers, "films"))
            .as("the TableRecord-sourced @service child fetcher reads the key columns with no "
                + "runtime fork on the parent's shape")
            .isTrue();
    }

    private static TypeSpec findType(String className, String sdl) {
        return ProjectionRenderTestSupport.renderProjections(TestSchemaHelper.buildSchema(sdl), DEFAULT_OUTPUT_PACKAGE).stream()
            .filter(t -> t.name().equals(className))
            .findFirst()
            .orElseThrow(() -> new AssertionError("Type class not found: " + className));
    }
}
