package no.sikt.graphitron.rewrite;

import no.sikt.graphitron.javapoet.TypeSpec;
import no.sikt.graphitron.rewrite.generators.TypeClassGenerator;
import no.sikt.graphitron.rewrite.generators.util.TypeSpecAssertions;
import org.junit.jupiter.api.Test;

import static no.sikt.graphitron.common.configuration.TestConfiguration.DEFAULT_OUTPUT_PACKAGE;
import static org.assertj.core.api.Assertions.assertThat;
import no.sikt.graphitron.rewrite.test.tier.PipelineTier;

/**
 * R425: SDL → classified schema → generated {@code TypeSpec} pipeline tests pinning that a
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

    private static TypeSpec findType(String className, String sdl) {
        return TypeClassGenerator.generate(TestSchemaHelper.buildSchema(sdl), DEFAULT_OUTPUT_PACKAGE).stream()
            .filter(t -> t.name().equals(className))
            .findFirst()
            .orElseThrow(() -> new AssertionError("Type class not found: " + className));
    }
}
