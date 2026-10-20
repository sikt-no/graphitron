package no.sikt.graphitron.rewrite;

import no.sikt.graphitron.model.diagnostics.Rejection;
import no.sikt.graphitron.model.diagnostics.ValidationError;
import no.sikt.graphitron.rewrite.model.CallSiteExtraction;
import no.sikt.graphitron.rewrite.model.ConditionFilter;
import no.sikt.graphitron.rewrite.model.GraphitronField.UnclassifiedField;
import no.sikt.graphitron.rewrite.model.ParamSource;
import no.sikt.graphitron.rewrite.model.QueryField;
import no.sikt.graphitron.rewrite.test.tier.PipelineTier;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Two halves of one guarantee about a {@code @condition(override: true)}: the bean a condition
 * receives is bound by the input's GraphQL field names, because on a filter input
 * {@code @field(name:)} names the column; and every filter slot whose implicit predicate the
 * override suppresses is read by some condition parameter, or the build fails naming it. Together
 * they make a client filter that changes nothing a build error rather than a query that matches
 * every row.
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

    // ===== A suppressed filter slot must have a reader =====

    @Test
    void aSetterBeanWithNoMemberForAColumnBackedFieldIsRejected() {
        var schema = build("", "filter: ReaderTwo", "titleOnly", "input: filter", true);
        assertThat(schema.field("Query", "films")).isInstanceOf(UnclassifiedField.class);
        assertThat(unread(schema)).singleElement().satisfies(u -> {
            assertThat(u.occurrencePath()).isEqualTo("Query.films(filter)/description");
            assertThat(u.beanMiss()).contains(new Rejection.AuthorError.OverrideSlotUnread.BeanMiss(
                STUB + "$TitleOnlyBean", "description"));
        });
        assertThat(located(schema)).singleElement()
            .satisfies(e -> assertThat(e.coordinate()).isEqualTo("ReaderTwo.description"));
    }

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
    void aMethodBindingNothingFromAnArgumentRejectsEveryFieldOfIt() {
        var schema = buildUnmapped("filter: ReaderTwo", "nothing");
        assertThat(unread(schema)).extracting(Rejection.AuthorError.OverrideSlotUnread::occurrencePath)
            .containsExactly("Query.films(filter)/title", "Query.films(filter)/description");
        assertThat(unread(schema)).allSatisfy(u -> assertThat(u.beanMiss()).isEmpty());
    }

    @Test
    void aTopLevelScalarArgumentLeftUnreadIsRejected() {
        var schema = buildUnmapped("title: String", "nothing");
        assertThat(schema.field("Query", "films")).isInstanceOf(UnclassifiedField.class);
        assertThat(unread(schema)).extracting(Rejection.AuthorError.OverrideSlotUnread::occurrencePath)
            .containsExactly("Query.films(title)");
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
    void aNestedBeanMissingAMemberIsRejectedNamingTheNestedBean() {
        var schema = build("", "filter: ReaderNested", "nestedSetter", "input: filter", true);
        assertThat(unread(schema)).singleElement().satisfies(u -> {
            assertThat(u.occurrencePath()).isEqualTo("Query.films(filter)/inner/length");
            assertThat(u.beanMiss()).contains(new Rejection.AuthorError.OverrideSlotUnread.BeanMiss(
                STUB + "$DescriptionBean", "length"));
        });
    }

    @Test
    void aHoistedGroupingLeafIsReadThroughItsAccessPath() {
        assertBuilds(build("", "filter: ReaderGrouped", "groupedBoth", "input: filter", true));
        var schema = build("", "filter: ReaderGrouped", "groupedDescription", "input: filter", true);
        assertThat(unread(schema)).singleElement().satisfies(u -> {
            assertThat(u.occurrencePath()).isEqualTo("Query.films(filter)/group/length");
            assertThat(u.beanMiss()).contains(new Rejection.AuthorError.OverrideSlotUnread.BeanMiss(
                STUB + "$DescriptionBean", "length"));
        });
    }

    @Test
    void aListOfInputsReadByAListShapedNestedBindingBuilds() {
        assertBuilds(build("", "filter: ReaderListed", "listedSetter", "input: filter", true));
    }

    @Test
    void aListOfInputsLeftUnreadIsRejectedOnceAsTheList() {
        var schema = build("", "filter: ReaderListedWithTitle", "oneString", "value: filter.title", true);
        assertThat(unread(schema)).extracting(Rejection.AuthorError.OverrideSlotUnread::occurrencePath)
            .containsExactly("Query.films(filter)/titles");
    }

    @Test
    void aNestedInputFieldConditionIsReadAtItsAbsolutePath() {
        // The condition's own ParamSource.Arg.path() is inner.description, relative to the input
        // field it sits on; only the absolute read filter/inner/description covers the slot.
        assertBuilds(nestedInputFieldCondition("ReaderInnerOne"));
        var schema = nestedInputFieldCondition("ReaderInner");
        assertThat(unread(schema)).extracting(Rejection.AuthorError.OverrideSlotUnread::occurrencePath)
            .containsExactly("Query.films(filter)/inner/length");
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

    private static GraphitronSchema buildUnmapped(String argDecl, String method) {
        return TestSchemaHelper.buildSchema(TYPES + """
            type Query {
              films(%s): [Film!]! @condition(condition: {className: "%s", method: "%s"}, override: true)
            }
            """.formatted(argDecl, STUB, method));
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
        assertThat(unread(schema)).isEmpty();
    }

    private static List<ValidationError> located(GraphitronSchema schema) {
        return schema.diagnostics().stream()
            .filter(e -> e.rejection() instanceof Rejection.AuthorError.OverrideSlotUnread)
            .toList();
    }

    private static List<Rejection.AuthorError.OverrideSlotUnread> unread(GraphitronSchema schema) {
        return located(schema).stream()
            .map(e -> (Rejection.AuthorError.OverrideSlotUnread) e.rejection())
            .toList();
    }
}
