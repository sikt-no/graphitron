package no.sikt.graphitron.rewrite;

import no.sikt.graphitron.rewrite.model.CallSiteExtraction;
import no.sikt.graphitron.rewrite.model.ConditionFilter;
import no.sikt.graphitron.rewrite.model.ParamSource;
import no.sikt.graphitron.rewrite.model.QueryField;
import no.sikt.graphitron.rewrite.test.tier.PipelineTier;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The bean a {@code @condition(override: true)} receives is bound by the input's GraphQL field
 * names, because on a filter input {@code @field(name:)} names the column; and a condition reading
 * every slot the override suppresses, whole, by a dotted path, through a grouping or a list, builds.
 * A slot no condition reads is ARGUMENT_UNBOUND in the store, pinned by its fact document.
 */
@PipelineTier
class ConditionOverrideReaderPipelineTest {

    private static final String STUB = "no.sikt.graphitron.rewrite.TestConditionReaderStub";

    private static final String TYPES = """
        type Film @table(name: "film") { title: String }
        input ReaderOne { title: String }
        input ReaderTwo { title: String description: String }
        input ReaderInner { description: String length: Int }
        input ReaderInnerOne { description: String }
        input ReaderNested { title: String inner: ReaderInner }
        input ReaderGrouped { group: ReaderInner }
        input ReaderListed { titles: [ReaderOne!] }
        input ReaderListedWithTitle { title: String titles: [ReaderOne!] }
        """;

    // ===== Bean members at a condition site match by GraphQL name =====

    @Test
    void aSetterBeanBindsAFieldWithFieldNameByItsGraphqlName() {
        var bean = inputBean(fieldLevel("input HeadingFilter { heading: String @field(name: \"title\") }",
            "filter: HeadingFilter", "headingSetter", "input: filter", true));
        assertThat(bean.target()).isEqualTo(CallSiteExtraction.InputBean.Target.JAVA_BEAN);
        assertThat(bean.fields()).singleElement().satisfies(fb -> {
            assertThat(fb.mapKey()).isEqualTo("heading");
            assertThat(fb.javaFieldName()).isEqualTo("heading");
        });
    }

    @Test
    void aRecordBindsAFieldWithFieldNameByItsGraphqlName() {
        var bean = inputBean(fieldLevel("input HeadingFilter { heading: String @field(name: \"title\") }",
            "filter: HeadingFilter", "headingRecord", "input: filter", true));
        assertThat(bean.target()).isEqualTo(CallSiteExtraction.InputBean.Target.RECORD);
        assertThat(bean.fields()).singleElement().satisfies(fb -> {
            assertThat(fb.mapKey()).isEqualTo("heading");
            assertThat(fb.javaFieldName()).isEqualTo("heading");
        });
    }

    @Test
    void aFieldWithFieldNameAndReferenceBindsByItsGraphqlName() {
        var bean = inputBean(fieldLevel("""
            input LanguageNameFilter {
              languageName: String @field(name: "name") @reference(path: [{key: "film_language_id_fkey"}])
            }
            """, "filter: LanguageNameFilter", "languageNameSetter", "input: filter", true));
        assertThat(bean.fields()).singleElement().satisfies(fb -> {
            assertThat(fb.mapKey()).isEqualTo("languageName");
            assertThat(fb.javaFieldName()).isEqualTo("languageName");
        });
    }

    // ===== A suppressed filter slot a condition reads builds =====

    @Test
    void theSameBeanUnderOverrideFalseBuilds() {
        assertBuilds(build("", "filter: ReaderTwo", "titleOnly", "input: filter", false));
    }

    @Test
    void aMapParameterReadsTheWholeInput() {
        assertBuilds(build("", "filter: ReaderTwo", "wholeMap", "input: filter", true));
    }

    @Test
    void aDottedPathReadsTheFieldItEndsOn() {
        assertBuilds(build("", "filter: ReaderOne", "oneString", "value: filter.title", true));
    }

    @Test
    void aFieldWithItsOwnConditionIsThatMethodsWhateverItDoes() {
        var schema = TestSchemaHelper.buildSchema(TYPES + """
            input ReaderOwn {
              title: String @condition(condition: {className: "%s", method: "ignoresTitle"})
            }
            type Query {
              films(filter: ReaderOwn): [Film!]! @condition(condition: {className: "%s", method: "nothing"}, override: true)
            }
            """.formatted(STUB, STUB));
        assertBuilds(schema);
    }

    @Test
    void aHoistedGroupingLeafIsReadThroughItsAccessPath() {
        assertBuilds(build("", "filter: ReaderGrouped", "groupedBoth", "input: filter", true));
    }

    @Test
    void aListOfInputsReadByAListShapedNestedBindingBuilds() {
        assertBuilds(build("", "filter: ReaderListed", "listedSetter", "input: filter", true));
    }

    @Test
    void aNestedInputFieldConditionIsReadAtItsAbsolutePath() {
        // The condition's own ParamSource.Arg.path() is inner.description, relative to the input
        // field it sits on; only the absolute read filter/inner/description covers the slot.
        assertBuilds(nestedInputFieldCondition("ReaderInnerOne"));
    }

    @Test
    void aDottedPathPastANodeIdLeafReadsTheLeaf() {
        // The descent continues past the input field into the decoded key's column, which reads
        // the slot's value through the projection rail; both spellings reach it.
        for (var mapping : List.of("languageId: pick.languageRef.language_id", "languageId: pick.languageRef")) {
            var schema = TestSchemaHelper.buildSchema("""
                interface Node { id: ID! }
                type Language implements Node @table(name: "language") @node { id: ID! @nodeId }
                type Film @table(name: "film") { title: String }
                input LanguagePick {
                  languageRef: ID @nodeId(typeName: "Language") @reference(path: [{key: "film_language_id_fkey"}])
                }
                type Query {
                  language: Language
                  films(pick: LanguagePick!): [Film!]! @condition(condition: {className: "no.sikt.graphitron.rewrite.TestConditionStub", method: "languageIdDecodedKeyCondition", argMapping: "%s"}, override: true)
                }
                """.formatted(mapping));
            assertBuilds(schema);
        }
    }

    // ===== helpers =====

    private static GraphitronSchema nestedInputFieldCondition(String innerType) {
        return TestSchemaHelper.buildSchema(TYPES + """
            input ReaderDeep {
              inner: %s @condition(condition: {className: "%s", method: "oneString", argMapping: "value: inner.description"}, override: true)
            }
            type Query { films(filter: ReaderDeep): [Film!]! }
            """.formatted(innerType, STUB));
    }

    private static GraphitronSchema build(String extraTypes, String argDecl, String method,
            String argMapping, boolean override) {
        return TestSchemaHelper.buildSchema(TYPES + extraTypes + """
            type Query {
              films(%s): [Film!]! @condition(condition: {className: "%s", method: "%s", argMapping: "%s"}, override: %s)
            }
            """.formatted(argDecl, STUB, method, argMapping, override));
    }

    private static QueryField.QueryTableField fieldLevel(String extraTypes, String argDecl,
            String method, String argMapping, boolean override) {
        var field = build(extraTypes, argDecl, method, argMapping, override).field("Query", "films");
        assertThat(field).isInstanceOf(QueryField.QueryTableField.class);
        return (QueryField.QueryTableField) field;
    }

    private static CallSiteExtraction.InputBean inputBean(QueryField.QueryTableField field) {
        var condition = field.filters().stream()
            .filter(f -> f instanceof ConditionFilter)
            .map(f -> (ConditionFilter) f)
            .findFirst().orElseThrow();
        var extraction = condition.params().stream()
            .filter(p -> p.source() instanceof ParamSource.Arg)
            .map(p -> ((ParamSource.Arg) p.source()).callSiteExtraction())
            .findFirst().orElseThrow();
        assertThat(extraction).isInstanceOf(CallSiteExtraction.InputBean.class);
        return (CallSiteExtraction.InputBean) extraction;
    }

    private static void assertBuilds(GraphitronSchema schema) {
        assertThat(schema.field("Query", "films"))
            .as("diagnostics: %s", TestSchemaHelper.diagnosticMessages(schema))
            .isInstanceOf(QueryField.QueryTableField.class);
    }
}
