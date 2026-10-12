package no.sikt.graphitron.rewrite;

import no.sikt.graphitron.model.diagnostics.WireCoercionError;
import no.sikt.graphitron.rewrite.model.CallSiteExtraction;
import no.sikt.graphitron.rewrite.model.ConditionFilter;
import no.sikt.graphitron.rewrite.model.GraphitronField;
import no.sikt.graphitron.rewrite.model.GraphitronField.UnclassifiedField;
import no.sikt.graphitron.rewrite.model.ParamSource;
import no.sikt.graphitron.rewrite.model.QueryField;
import no.sikt.graphitron.rewrite.model.WhereFilter;
import no.sikt.graphitron.rewrite.test.tier.PipelineTier;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The carrier a {@code @condition} parameter bound to a GraphQL input-object slot receives.
 * graphql-java delivers an input object as a {@code Map}; the generated glue used to cast that map
 * straight to the declared parameter type, so a parameter typed as a consumer class compiled and
 * threw {@code ClassCastException} on every request. The declared type is now decided against the
 * slot by the decision the {@code @service} input-bean path makes, under the condition's policy: a
 * {@code Map}-shaped parameter passes through, a bean is built from the map where the consuming
 * coordinate is known, and everything else is a build error naming what to declare.
 *
 * <p>All three slot-typed entry points are covered, since all three reach the decision through
 * {@link ServiceCatalog#reflectTableMethod}: field-level (here via {@code argMapping}),
 * argument-level, and input-field-level.
 */
@PipelineTier
class ConditionParameterCarrierPipelineTest {

    private static final String STUB = "no.sikt.graphitron.rewrite.TestConditionCarrierStub";
    private static final String MAP = "java.util.Map<java.lang.String, java.lang.Object>";

    private static final String TYPES = """
        type Film @table(name: "film") { title: String }
        input CarrierFilter { title: String }
        input CarrierOuter { title: String inner: CarrierFilter inners: [CarrierFilter!] }
        input CarrierNestedFilter { title: String inner: CarrierFilter }
        """;

    // ===== Map shapes pass through =====

    @Test
    void mapShapedParametersPassThroughAsDirect() {
        for (var method : List.of("carrierMap", "carrierWildcardMap", "carrierObject")) {
            var cf = fieldCondition(fieldLevel("filter: CarrierFilter", method, "input: filter"));
            assertThat(argExtraction(cf, "input"))
                .as("%s: graphql-java delivers a Map, which this type takes as it is", method)
                .isInstanceOf(CallSiteExtraction.Direct.class);
        }
    }

    @Test
    void aListOfMapsOnAListSlotPassesThroughAsDirect() {
        var cf = fieldCondition(fieldLevel("filter: [CarrierFilter!]", "carrierMapList", "input: filter"));
        assertThat(argExtraction(cf, "input")).isInstanceOf(CallSiteExtraction.Direct.class);
    }

    @Test
    void aMapAtAnInputFieldConditionPassesThroughAsDirect() {
        var schema = TestSchemaHelper.buildSchema(TYPES + """
            input FilmWrap {
              inner: CarrierFilter @condition(condition: {className: "%s", method: "carrierMapInner"}, override: true)
            }
            type Query { films(wrap: FilmWrap): [Film!]! }
            """.formatted(STUB));
        assertThat(schema.field("Query", "films")).isNotInstanceOf(UnclassifiedField.class);
    }

    @Test
    void aDottedPathToAnInputObjectTakesAMap() {
        var cf = fieldCondition(fieldLevel("outer: CarrierOuter", "carrierMap", "input: outer.inner"));
        var extraction = argExtraction(cf, "input");
        assertThat(extraction).isInstanceOf(CallSiteExtraction.NestedInputField.class);
        assertThat(((CallSiteExtraction.NestedInputField) extraction).leaf())
            .isInstanceOf(CallSiteExtraction.Direct.class);
    }

    // ===== A bean is built where the consuming coordinate is known =====

    @Test
    void aRecordAtAFieldLevelConditionIsBuiltFromTheMap() {
        var bean = inputBean(fieldCondition(fieldLevel("filter: CarrierFilter", "carrierRecord", "input: filter")), "input");
        assertThat(bean.beanClass().simpleName()).isEqualTo("TestConditionCarrierStub$CarrierRecord");
        assertThat(bean.target()).isEqualTo(CallSiteExtraction.InputBean.Target.RECORD);
        assertThat(bean.fields()).extracting(CallSiteExtraction.FieldBinding::javaFieldName)
            .containsExactly("title");
    }

    @Test
    void aRecordAtAnArgumentLevelConditionIsBuiltFromTheMap() {
        var schema = TestSchemaHelper.buildSchema(TYPES + """
            type Query {
              films(input: CarrierFilter @condition(condition: {className: "%s", method: "carrierRecord"}, override: true)): [Film!]!
            }
            """.formatted(STUB));
        var field = schema.field("Query", "films");
        assertThat(field).isInstanceOf(QueryField.QueryTableField.class);
        var bean = inputBean(conditionFilter(((QueryField.QueryTableField) field).filters()), "input");
        assertThat(bean.target()).isEqualTo(CallSiteExtraction.InputBean.Target.RECORD);
    }

    @Test
    void aSetterBeanWithANestedBeanMemberIsBuilt() {
        var bean = inputBean(fieldCondition(fieldLevel("filter: CarrierNestedFilter", "carrierSetterBean", "input: filter")), "input");
        assertThat(bean.target()).isEqualTo(CallSiteExtraction.InputBean.Target.JAVA_BEAN);
        var inner = bean.fields().stream()
            .filter(fb -> fb.javaFieldName().equals("inner")).findFirst().orElseThrow();
        assertThat(inner.leaf()).isInstanceOf(CallSiteExtraction.InputBean.class);
        assertThat(((CallSiteExtraction.InputBean) inner.leaf()).target())
            .isEqualTo(CallSiteExtraction.InputBean.Target.JAVA_BEAN);
    }

    @Test
    void aDottedPathToAnInputObjectBuildsABeanAtTheLeaf() {
        var cf = fieldCondition(fieldLevel("outer: CarrierOuter", "carrierRecord", "input: outer.inner"));
        var extraction = argExtraction(cf, "input");
        assertThat(extraction).isInstanceOf(CallSiteExtraction.NestedInputField.class);
        var nested = (CallSiteExtraction.NestedInputField) extraction;
        assertThat(nested.path()).containsExactly("inner");
        assertThat(nested.leaf()).isInstanceOf(CallSiteExtraction.InputBean.class);
    }

    @Test
    void aListOfRecordsOnAListSlotIsBuilt() {
        var bean = inputBean(fieldCondition(fieldLevel("filter: [CarrierFilter!]", "carrierRecordList", "input: filter")), "input");
        assertThat(bean.target()).isEqualTo(CallSiteExtraction.InputBean.Target.RECORD);
    }

    // ===== What is not built is refused =====

    @Test
    void aBeanAtAnInputFieldConditionIsRefusedNamingTheInputField() {
        var schema = TestSchemaHelper.buildSchema(TYPES + """
            input FilmWrap {
              inner: CarrierFilter @condition(condition: {className: "%s", method: "carrierRecordInner"}, override: true)
            }
            type Query { films(wrap: FilmWrap): [Film!]! }
            """.formatted(STUB));
        assertThat(schema.field("Query", "films")).isInstanceOf(UnclassifiedField.class);
        assertThat(TestSchemaHelper.diagnosticMessages(schema))
            .contains("parameter 'inner' of condition method 'carrierRecordInner'")
            .contains("a @condition on input field 'FilmWrap.inner'")
            .contains("java.util.Map<String, Object>")
            .contains("move the @condition to the consuming field");
    }

    @Test
    void aJooqRecordParameterIsRefused() {
        var field = fieldLevel("filter: CarrierFilter", "carrierJooqRecord", "input: filter");
        assertThat(field).isInstanceOf(UnclassifiedField.class);
        var rejection = ((UnclassifiedField) field).rejection();
        assertThat(rejection).isInstanceOf(WireCoercionError.ConditionJooqRecordCarrier.class);
        assertThat(((WireCoercionError.ConditionJooqRecordCarrier) rejection).declaredType())
            .isEqualTo("no.sikt.graphitron.rewrite.test.jooq.public_.tables.records.FilmRecord");
    }

    @Test
    void unbuildableBeansAreRefusedWithTheSharedMessages() {
        assertThat(refusal(fieldLevel("filter: CarrierFilter", "carrierNoNoArgConstructor", "input: filter")))
            .contains("has no public no-arg constructor");
        assertThat(refusal(fieldLevel("filter: CarrierFilter", "carrierRecordExtraComponent", "input: filter")))
            .contains("component 'rating'")
            .contains("every record component must bind");
        assertThat(refusal(fieldLevel("filter: CarrierFilter", "carrierInterface", "input: filter")))
            .contains("is abstract or an interface");
    }

    @Test
    void aSetOfBeansIsRefusedAsAssignability() {
        assertThat(assignability(fieldLevel("filter: [CarrierFilter!]", "carrierRecordSet", "input: filter"))
            .coercionOutputType()).isEqualTo("java.util.List<" + MAP + ">");
    }

    @Test
    void aPathThroughAListIsRefusedEvenForAMap() {
        var a = assignability(fieldLevel("outers: [CarrierOuter!]", "carrierMap", "input: outers.inner"));
        assertThat(a.site()).contains("passes through a list");
    }

    @Test
    void aNonCarrierTypeIsRefusedAsAssignability() {
        var a = assignability(fieldLevel("filter: CarrierFilter", "carrierString", "input: filter"));
        assertThat(a.declaredType()).isEqualTo("java.lang.String");
        assertThat(a.coercionOutputType()).isEqualTo(MAP);
    }

    @Test
    void aNonCarrierTypeAtAnArgumentLevelConditionFailsTheField() {
        // The argument's refusal reaches the field: before, an argument whose condition was refused
        // still classified, and the field built without the filter the author wrote.
        var schema = TestSchemaHelper.buildSchema(TYPES + """
            type Query {
              films(input: CarrierFilter @condition(condition: {className: "%s", method: "carrierString"}, override: true)): [Film!]!
            }
            """.formatted(STUB));
        var a = assignability(schema.field("Query", "films"));
        assertThat(a.declaredType()).isEqualTo("java.lang.String");
        assertThat(a.site()).contains("parameter 'input' of condition method 'carrierString'");
    }

    @Test
    void listShapesThatAreNotAListOfMapsAreRefused() {
        assertThat(assignability(fieldLevel("filter: [CarrierFilter!]", "carrierMapSet", "input: filter"))
            .coercionOutputType()).isEqualTo("java.util.List<" + MAP + ">");
        assertThat(assignability(fieldLevel("filter: [CarrierFilter!]", "carrierMap", "input: filter"))
            .declaredType()).isEqualTo("java.util.Map<java.lang.String, java.lang.Object>");
    }

    // ===== helpers =====

    private static GraphitronField fieldLevel(String argDecl, String method, String argMapping) {
        var schema = TestSchemaHelper.buildSchema(TYPES + """
            type Query {
              films(%s): [Film!]! @condition(condition: {className: "%s", method: "%s", argMapping: "%s"}, override: true)
            }
            """.formatted(argDecl, STUB, method, argMapping));
        return schema.field("Query", "films");
    }

    private static WireCoercionError.Assignability assignability(GraphitronField field) {
        assertThat(field).isInstanceOf(UnclassifiedField.class);
        var rejection = ((UnclassifiedField) field).rejection();
        assertThat(rejection).isInstanceOf(WireCoercionError.Assignability.class);
        return (WireCoercionError.Assignability) rejection;
    }

    private static String refusal(GraphitronField field) {
        assertThat(field).isInstanceOf(UnclassifiedField.class);
        return ((UnclassifiedField) field).rejection().message();
    }

    private static CallSiteExtraction.InputBean inputBean(ConditionFilter cf, String paramName) {
        var extraction = argExtraction(cf, paramName);
        assertThat(extraction).isInstanceOf(CallSiteExtraction.InputBean.class);
        return (CallSiteExtraction.InputBean) extraction;
    }

    private static ConditionFilter fieldCondition(GraphitronField field) {
        assertThat(field).isInstanceOf(QueryField.QueryTableField.class);
        return conditionFilter(((QueryField.QueryTableField) field).filters());
    }

    private static CallSiteExtraction argExtraction(ConditionFilter cf, String paramName) {
        return cf.params().stream()
            .filter(p -> p.name().equals(paramName))
            .map(p -> ((ParamSource.Arg) p.source()).callSiteExtraction())
            .findFirst()
            .orElseThrow(() -> new AssertionError("no parameter '" + paramName + "' in " + cf));
    }

    private static ConditionFilter conditionFilter(List<WhereFilter> filters) {
        return filters.stream()
            .filter(f -> f instanceof ConditionFilter)
            .map(f -> (ConditionFilter) f)
            .findFirst()
            .orElseThrow(() -> new AssertionError("no developer @condition in " + filters));
    }
}
