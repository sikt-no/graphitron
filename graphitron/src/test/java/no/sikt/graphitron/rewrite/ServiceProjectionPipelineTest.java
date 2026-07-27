package no.sikt.graphitron.rewrite;

import no.sikt.graphitron.javapoet.TypeSpec;
import no.sikt.graphitron.rewrite.generators.TypeClassGenerator;
import no.sikt.graphitron.rewrite.generators.TypeFetcherGenerator;
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
 * the client's selection contains no field mapping to them; otherwise the key extraction reads
 * {@code null} and the child silently resolves to {@code null} (the federation
 * {@code _entities}-fetch shape). Split-{@code @reference} children get the same treatment via
 * the shared {@link no.sikt.graphitron.rewrite.model.BatchKeyField} arm in
 * {@code TypeClassGenerator.collectRequiredProjection}; their coverage lives in
 * {@link NestingFieldPipelineTest}.
 *
 * <p>Every fixture's service method carries a Sources param ({@code Set<Row1<Integer>>}), so the
 * field classifies with a non-null {@code SourceKey} (a no-Sources method is a plain per-parent
 * delegation with no key read and no projection need). Every fixture's parent type deliberately
 * carries <em>no</em> other force-projecting child ({@code @splitQuery} sibling), so a regression of the {@code BatchKeyField} arm turns these red rather than being
 * masked by an unrelated sibling's projection.
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
     * table type's {@code $fields}; the recursion in {@code collectRequiredProjection}
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

        assertThat(TypeSpecAssertions.appendsRequiredColumn(languageType, "LANGUAGE_ID"))
            .as("parent $fields force-projects the TableRecord-sourced child's key column")
            .isTrue();
        assertThat(TypeSpecAssertions.appendsRequiredColumn(languageType, "NAME"))
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

        assertThat(TypeSpecAssertions.appendsRequiredColumn(languageType, "LANGUAGE_ID"))
            .as("parent $fields force-projects the TableRecord-sourced child's key column")
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

        assertThat(TypeSpecAssertions.appendsRequiredColumn(languageType, "LANGUAGE_ID"))
            .as("Record1-sourced @service child force-projects its SourceKey column")
            .isTrue();
    }

    /**
     * A TableRecord-sourced {@code @service} child nested under a plain-object
     * {@code NestingField} shares the outer table type's {@code $fields}; the recursion in
     * {@code collectRequiredProjection} must surface the key requirement onto the outer parent.
     * The projected fields are the outer parent table's by construction:
     * {@code emitSelectionSwitch} threads {@code tableArg} unchanged into nested depths, so the
     * nested child's key read resolves against the outer table's row.
     */
    @Test
    void nestedTableRecordServiceChild_projectsKeyColumnOnOuterParent() {
        var languageType = findType("Language", """
            type Language @table(name: "language") { info: LanguageInfo }
            type LanguageInfo {
                rank: Int @service(
                    service: {className: "no.sikt.graphitron.rewrite.generators.TestFilmService", method: "getRankMappedByRecord"}
                )
            }
            type Query { language: Language }
            """);

        assertThat(TypeSpecAssertions.appendsRequiredColumn(languageType, "LANGUAGE_ID"))
            .as("outer parent $fields force-projects the nested TableRecord-sourced child's key column")
            .isTrue();
    }

    /**
     * A parent with both a {@code TableRecord}-wrap {@code @service} child and a {@code Wrap.Row}
     * {@code @splitQuery} sibling. Both demand the same base-named key column and the deduping
     * accumulator collapses them to one projection term; nothing widens.
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

        assertThat(TypeSpecAssertions.appendsRequiredColumn(languageType, "LANGUAGE_ID"))
            .as("both children force-project the same base-named key column")
            .isTrue();
        assertThat(TypeSpecAssertions.appendsRequiredColumn(languageType, "NAME"))
            .as("neither child widens the projection beyond the key")
            .isFalse();
    }

    /**
     * The consumer side of the typed-record shape: the generated {@code @service}-child fetcher's
     * key extraction is one unconditional per-key-column read, with no runtime branch on the
     * parent source's type. It can be unconditional because the key columns are present under
     * their base names on both parent arrival shapes: force-projected when the parent came from
     * {@code $fields} (pinned by the producer-side tests above), and carried as real columns when
     * a service hands back its own typed record. A shape assertion, not a full code-string pin.
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
        return TypeClassGenerator.generate(TestSchemaHelper.buildSchema(sdl), DEFAULT_OUTPUT_PACKAGE).stream()
            .filter(t -> t.name().equals(className))
            .findFirst()
            .orElseThrow(() -> new AssertionError("Type class not found: " + className));
    }
}
