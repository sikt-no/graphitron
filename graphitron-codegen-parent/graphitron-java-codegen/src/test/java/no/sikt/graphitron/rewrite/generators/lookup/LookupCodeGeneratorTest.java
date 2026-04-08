package no.sikt.graphitron.rewrite.generators.lookup;

import no.sikt.graphitron.javapoet.MethodSpec;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class LookupCodeGeneratorTest {

    private static final LookupCodeGenerator GEN = new LookupCodeGenerator();

    private static MethodSpec method(LookupSpec spec) {
        return GEN.generate(spec).methodSpecs().get(0);
    }

    // ===== Common assertions =====

    @Test
    void generate_classNamedAfterTypeName() {
        var spec = inputTypeSpec("Customer", "input",
            new LookupInputFieldSpec("customerId", "java.lang.Integer", false));
        assertThat(GEN.generate(spec).name()).isEqualTo("CustomerLookup");
    }

    @Test
    void generate_containsToInputRowsMethod() {
        var spec = inputTypeSpec("Customer", "input",
            new LookupInputFieldSpec("customerId", "java.lang.Integer", false));
        assertThat(method(spec).name()).isEqualTo("toInputRows");
    }

    @Test
    void generate_methodIsPublicStatic() {
        var spec = inputTypeSpec("Customer", "input",
            new LookupInputFieldSpec("customerId", "java.lang.Integer", false));
        assertThat(method(spec).modifiers())
            .containsExactlyInAnyOrder(javax.lang.model.element.Modifier.PUBLIC, javax.lang.model.element.Modifier.STATIC);
    }

    @Test
    void generate_parameterIsArgumentsMap() {
        var spec = inputTypeSpec("Customer", "input",
            new LookupInputFieldSpec("customerId", "java.lang.Integer", false));
        assertThat(method(spec).parameters()).hasSize(1);
        assertThat(method(spec).parameters().get(0).type().toString())
            .isEqualTo("java.util.Map<java.lang.String, java.lang.Object>");
    }

    // ===== Input-type case =====

    @Test
    void inputType_extractsListFromArguments() {
        var spec = inputTypeSpec("Customer", "input",
            new LookupInputFieldSpec("customerId", "java.lang.Integer", false));
        assertThat(method(spec).code().toString()).contains("arguments.get(\"input\")");
    }

    @Test
    void inputType_declaresLocalVar() {
        var spec = inputTypeSpec("Customer", "input",
            new LookupInputFieldSpec("customerId", "java.lang.Integer", false));
        assertThat(method(spec).code().toString()).contains("input =");
    }

    @Test
    void inputType_iteratesOverLocalVar() {
        var spec = inputTypeSpec("Customer", "input",
            new LookupInputFieldSpec("customerId", "java.lang.Integer", false));
        String code = method(spec).code().toString();
        assertThat(code).contains("input.size()").contains("input.get(i)");
    }

    @Test
    void inputType_valuesReadFromElementMap() {
        var spec = inputTypeSpec("Customer", "input",
            new LookupInputFieldSpec("customerId", "java.lang.Integer", false));
        assertThat(method(spec).code().toString()).contains("m.get(\"customerId\")");
    }

    @Test
    void inputType_returnTypeIsRow2ForOneField() {
        var spec = inputTypeSpec("Customer", "input",
            new LookupInputFieldSpec("customerId", "java.lang.Integer", false));
        assertThat(method(spec).returnType().toString())
            .isEqualTo("java.util.List<org.jooq.Row2<java.lang.Integer, java.lang.Integer>>");
    }

    @Test
    void inputType_returnTypeIsRow3ForTwoFields() {
        var spec = new LookupSpec("Customer", "input", List.of(
            new LookupInputFieldSpec("customerId", "java.lang.Integer", false),
            new LookupInputFieldSpec("email", "java.lang.String", false)
        ));
        assertThat(method(spec).returnType().toString())
            .isEqualTo("java.util.List<org.jooq.Row3<java.lang.Integer, java.lang.Integer, java.lang.String>>");
    }

    @Test
    void inputType_bodyUsesDslRowAndIntStream() {
        var spec = inputTypeSpec("Customer", "input",
            new LookupInputFieldSpec("customerId", "java.lang.Integer", false));
        String code = method(spec).code().toString();
        assertThat(code)
            .contains("IntStream.range(0,")
            .contains("DSL.row(")
            .contains(".toList()");
    }

    // ===== Flat-args case =====

    @Test
    void flatArgs_listArgDeclaredAsLocalVar() {
        var spec = flatSpec("Person",
            new LookupInputFieldSpec("ids", "java.lang.String", true),
            new LookupInputFieldSpec("tenantId", "java.lang.String", false));
        String code = method(spec).code().toString();
        assertThat(code).contains("ids =").contains("arguments.get(\"ids\")");
    }

    @Test
    void flatArgs_scalarArgReadInlineWithCast() {
        var spec = flatSpec("Person",
            new LookupInputFieldSpec("ids", "java.lang.String", true),
            new LookupInputFieldSpec("tenantId", "java.lang.String", false));
        assertThat(method(spec).code().toString()).contains("arguments.get(\"tenantId\")");
    }

    @Test
    void flatArgs_listArgValueUsesGetI() {
        var spec = flatSpec("Person",
            new LookupInputFieldSpec("ids", "java.lang.String", true));
        assertThat(method(spec).code().toString()).contains("ids.get(i)");
    }

    @Test
    void flatArgs_sizeFromFirstListArg() {
        var spec = flatSpec("Person",
            new LookupInputFieldSpec("ids", "java.lang.String", true),
            new LookupInputFieldSpec("tenantId", "java.lang.String", false));
        assertThat(method(spec).code().toString()).contains("ids.size()");
    }

    @Test
    void flatArgs_noInputVarDeclaration() {
        var spec = flatSpec("Person",
            new LookupInputFieldSpec("ids", "java.lang.String", true));
        assertThat(method(spec).code().toString()).doesNotContain("input =");
    }

    @Test
    void flatArgs_returnTypeCorrect() {
        var spec = flatSpec("Person",
            new LookupInputFieldSpec("ids", "java.lang.String", true),
            new LookupInputFieldSpec("tenantId", "java.lang.String", false));
        assertThat(method(spec).returnType().toString())
            .isEqualTo("java.util.List<org.jooq.Row3<java.lang.Integer, java.lang.String, java.lang.String>>");
    }

    // ===== Helpers =====

    private static LookupSpec inputTypeSpec(String typeName, String inputArgName,
            LookupInputFieldSpec... fields) {
        return new LookupSpec(typeName, inputArgName, List.of(fields));
    }

    private static LookupSpec flatSpec(String typeName, LookupInputFieldSpec... fields) {
        return new LookupSpec(typeName, null, List.of(fields));
    }
}
