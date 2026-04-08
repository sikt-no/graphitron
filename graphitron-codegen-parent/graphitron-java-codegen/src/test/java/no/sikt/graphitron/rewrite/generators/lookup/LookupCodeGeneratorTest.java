package no.sikt.graphitron.rewrite.generators.lookup;

import no.sikt.graphitron.javapoet.ClassName;
import no.sikt.graphitron.javapoet.MethodSpec;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class LookupCodeGeneratorTest {

    private static final ClassName TABLES = ClassName.get("test.jooq", "Tables");
    private static final ClassName GRAPHITRON_VALUES = ClassName.get("test.rewrite", "GraphitronValues");
    private static final LookupCodeGenerator GEN = new LookupCodeGenerator(TABLES, GRAPHITRON_VALUES);

    private static MethodSpec method(LookupSpec spec) {
        return GEN.generate(spec).methodSpecs().get(0);
    }

    // ===== Common assertions =====

    @Test
    void generate_classNamedAfterTypeName() {
        var spec = inputTypeSpec("Customer", "CUSTOMER", "input",
            new LookupInputFieldSpec("customerId", "CUSTOMER_ID", "java.lang.Integer", false));
        assertThat(GEN.generate(spec).name()).isEqualTo("CustomerLookup");
    }

    @Test
    void generate_containsToInputRowsMethod() {
        var spec = inputTypeSpec("Customer", "CUSTOMER", "input",
            new LookupInputFieldSpec("customerId", "CUSTOMER_ID", "java.lang.Integer", false));
        assertThat(method(spec).name()).isEqualTo("toInputRows");
    }

    @Test
    void generate_methodIsPublicStatic() {
        var spec = inputTypeSpec("Customer", "CUSTOMER", "input",
            new LookupInputFieldSpec("customerId", "CUSTOMER_ID", "java.lang.Integer", false));
        assertThat(method(spec).modifiers())
            .containsExactlyInAnyOrder(javax.lang.model.element.Modifier.PUBLIC, javax.lang.model.element.Modifier.STATIC);
    }

    @Test
    void generate_firstParameterIsDslContext() {
        var spec = inputTypeSpec("Customer", "CUSTOMER", "input",
            new LookupInputFieldSpec("customerId", "CUSTOMER_ID", "java.lang.Integer", false));
        assertThat(method(spec).parameters().get(0).type().toString())
            .isEqualTo("org.jooq.DSLContext");
    }

    @Test
    void generate_secondParameterIsMapOfStringObject() {
        var spec = inputTypeSpec("Customer", "CUSTOMER", "input",
            new LookupInputFieldSpec("customerId", "CUSTOMER_ID", "java.lang.Integer", false));
        assertThat(method(spec).parameters().get(1).type().toString())
            .isEqualTo("java.util.Map<java.lang.String, java.lang.Object>");
    }

    // ===== Input-type case =====

    @Test
    void inputType_extractsListFromArguments() {
        var spec = inputTypeSpec("Customer", "CUSTOMER", "input",
            new LookupInputFieldSpec("customerId", "CUSTOMER_ID", "java.lang.Integer", false));
        assertThat(method(spec).code().toString()).contains("arguments.get(\"input\")");
    }

    @Test
    void inputType_declaresLocalVar() {
        var spec = inputTypeSpec("Customer", "CUSTOMER", "input",
            new LookupInputFieldSpec("customerId", "CUSTOMER_ID", "java.lang.Integer", false));
        assertThat(method(spec).code().toString()).contains("input =");
    }

    @Test
    void inputType_iteratesOverLocalVar() {
        var spec = inputTypeSpec("Customer", "CUSTOMER", "input",
            new LookupInputFieldSpec("customerId", "CUSTOMER_ID", "java.lang.Integer", false));
        String code = method(spec).code().toString();
        assertThat(code).contains("input.size()").contains("input.get(i)");
    }

    @Test
    void inputType_valuesReadFromElementMap() {
        var spec = inputTypeSpec("Customer", "CUSTOMER", "input",
            new LookupInputFieldSpec("customerId", "CUSTOMER_ID", "java.lang.Integer", false));
        assertThat(method(spec).code().toString()).contains("m.get(\"customerId\")");
    }

    @Test
    void inputType_returnTypeIsRecord2ForOneField() {
        var spec = inputTypeSpec("Customer", "CUSTOMER", "input",
            new LookupInputFieldSpec("customerId", "CUSTOMER_ID", "java.lang.Integer", false));
        assertThat(method(spec).returnType().toString())
            .isEqualTo("java.util.List<org.jooq.Record2<java.lang.Integer, java.lang.Integer>>");
    }

    @Test
    void inputType_returnTypeIsRecord3ForTwoFields() {
        var spec = new LookupSpec("Customer", "CUSTOMER", "input", List.of(
            new LookupInputFieldSpec("customerId", "CUSTOMER_ID", "java.lang.Integer", false),
            new LookupInputFieldSpec("email", "EMAIL", "java.lang.String", false)
        ));
        assertThat(method(spec).returnType().toString())
            .isEqualTo("java.util.List<org.jooq.Record3<java.lang.Integer, java.lang.Integer, java.lang.String>>");
    }

    @Test
    void inputType_newRecordIncludesTableColumn() {
        var spec = inputTypeSpec("Customer", "CUSTOMER", "input",
            new LookupInputFieldSpec("customerId", "CUSTOMER_ID", "java.lang.Integer", false));
        assertThat(method(spec).code().toString()).contains("CUSTOMER.CUSTOMER_ID");
    }

    @Test
    void inputType_bodyUsesIntStreamAndDsl() {
        var spec = inputTypeSpec("Customer", "CUSTOMER", "input",
            new LookupInputFieldSpec("customerId", "CUSTOMER_ID", "java.lang.Integer", false));
        String code = method(spec).code().toString();
        assertThat(code)
            .contains("IntStream.range(0,")
            .contains("ctx.newRecord(")
            .contains("GRAPHITRON_INPUT_IDX")
            .contains(".toList()");
    }

    // ===== Flat-args case =====

    @Test
    void flatArgs_listArgDeclaredAsLocalVar() {
        var spec = flatSpec("Person", "PERSON",
            new LookupInputFieldSpec("ids", "PERSON_ID", "java.lang.String", true),
            new LookupInputFieldSpec("tenantId", "TENANT_ID", "java.lang.String", false));
        String code = method(spec).code().toString();
        assertThat(code).contains("ids =").contains("arguments.get(\"ids\")");
    }

    @Test
    void flatArgs_scalarArgReadInlineWithCast() {
        var spec = flatSpec("Person", "PERSON",
            new LookupInputFieldSpec("ids", "PERSON_ID", "java.lang.String", true),
            new LookupInputFieldSpec("tenantId", "TENANT_ID", "java.lang.String", false));
        assertThat(method(spec).code().toString()).contains("arguments.get(\"tenantId\")");
    }

    @Test
    void flatArgs_listArgValueUsesGetI() {
        var spec = flatSpec("Person", "PERSON",
            new LookupInputFieldSpec("ids", "PERSON_ID", "java.lang.String", true));
        assertThat(method(spec).code().toString()).contains("ids.get(i)");
    }

    @Test
    void flatArgs_sizeFromFirstListArg() {
        var spec = flatSpec("Person", "PERSON",
            new LookupInputFieldSpec("ids", "PERSON_ID", "java.lang.String", true),
            new LookupInputFieldSpec("tenantId", "TENANT_ID", "java.lang.String", false));
        assertThat(method(spec).code().toString()).contains("ids.size()");
    }

    @Test
    void flatArgs_noInputVarDeclaration() {
        var spec = flatSpec("Person", "PERSON",
            new LookupInputFieldSpec("ids", "PERSON_ID", "java.lang.String", true));
        assertThat(method(spec).code().toString()).doesNotContain("input =");
    }

    @Test
    void flatArgs_returnTypeCorrect() {
        var spec = flatSpec("Person", "PERSON",
            new LookupInputFieldSpec("ids", "PERSON_ID", "java.lang.String", true),
            new LookupInputFieldSpec("tenantId", "TENANT_ID", "java.lang.String", false));
        assertThat(method(spec).returnType().toString())
            .isEqualTo("java.util.List<org.jooq.Record3<java.lang.Integer, java.lang.String, java.lang.String>>");
    }

    // ===== Helpers =====

    private static LookupSpec inputTypeSpec(String typeName, String table, String inputArgName,
            LookupInputFieldSpec... fields) {
        return new LookupSpec(typeName, table, inputArgName, List.of(fields));
    }

    private static LookupSpec flatSpec(String typeName, String table, LookupInputFieldSpec... fields) {
        return new LookupSpec(typeName, table, null, List.of(fields));
    }
}
