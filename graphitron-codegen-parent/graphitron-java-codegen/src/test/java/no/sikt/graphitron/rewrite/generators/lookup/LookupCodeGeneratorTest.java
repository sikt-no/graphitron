package no.sikt.graphitron.rewrite.generators.lookup;

import no.sikt.graphitron.javapoet.JavaFile;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class LookupCodeGeneratorTest {

    private static final LookupCodeGenerator GEN = new LookupCodeGenerator();

    private static String render(LookupSpec spec) {
        var typeSpec = GEN.generate(spec);
        return JavaFile.builder("test.pkg", typeSpec).indent("    ").build().toString();
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
        assertThat(render(spec)).contains("toInputRows");
    }

    @Test
    void generate_methodIsPublicStatic() {
        var spec = inputTypeSpec("Customer", "CUSTOMER", "input",
            new LookupInputFieldSpec("customerId", "CUSTOMER_ID", "java.lang.Integer", false));
        assertThat(render(spec)).contains("public static");
    }

    @Test
    void generate_firstParameterIsDslContext() {
        var spec = inputTypeSpec("Customer", "CUSTOMER", "input",
            new LookupInputFieldSpec("customerId", "CUSTOMER_ID", "java.lang.Integer", false));
        assertThat(render(spec)).contains("DSLContext ctx");
    }

    @Test
    void generate_secondParameterIsMapOfStringObject() {
        var spec = inputTypeSpec("Customer", "CUSTOMER", "input",
            new LookupInputFieldSpec("customerId", "CUSTOMER_ID", "java.lang.Integer", false));
        assertThat(render(spec)).contains("Map<String, Object> arguments");
    }

    // ===== Input-type case =====

    @Test
    void inputType_extractsListFromArguments() {
        var spec = inputTypeSpec("Customer", "CUSTOMER", "input",
            new LookupInputFieldSpec("customerId", "CUSTOMER_ID", "java.lang.Integer", false));
        assertThat(render(spec)).contains("arguments.get(\"input\")");
    }

    @Test
    void inputType_declaresLocalVar() {
        var spec = inputTypeSpec("Customer", "CUSTOMER", "input",
            new LookupInputFieldSpec("customerId", "CUSTOMER_ID", "java.lang.Integer", false));
        assertThat(render(spec)).contains("List<Map<String, Object>> input =");
    }

    @Test
    void inputType_iteratesOverLocalVar() {
        var spec = inputTypeSpec("Customer", "CUSTOMER", "input",
            new LookupInputFieldSpec("customerId", "CUSTOMER_ID", "java.lang.Integer", false));
        assertThat(render(spec)).contains("input.size()").contains("input.get(i)");
    }

    @Test
    void inputType_valuesReadFromElementMap() {
        var spec = inputTypeSpec("Customer", "CUSTOMER", "input",
            new LookupInputFieldSpec("customerId", "CUSTOMER_ID", "java.lang.Integer", false));
        assertThat(render(spec)).contains("(Integer) m.get(\"customerId\")");
    }

    @Test
    void inputType_returnTypeIsRecord2ForOneField() {
        var spec = inputTypeSpec("Customer", "CUSTOMER", "input",
            new LookupInputFieldSpec("customerId", "CUSTOMER_ID", "java.lang.Integer", false));
        assertThat(render(spec)).contains("Record2<Integer, Integer>");
    }

    @Test
    void inputType_returnTypeIsRecord3ForTwoFields() {
        var spec = new LookupSpec("Customer", "CUSTOMER", "input", List.of(
            new LookupInputFieldSpec("customerId", "CUSTOMER_ID", "java.lang.Integer", false),
            new LookupInputFieldSpec("email", "EMAIL", "java.lang.String", false)
        ));
        assertThat(render(spec)).contains("Record3<Integer, Integer, String>");
    }

    @Test
    void inputType_newRecordIncludesTableColumn() {
        var spec = inputTypeSpec("Customer", "CUSTOMER", "input",
            new LookupInputFieldSpec("customerId", "CUSTOMER_ID", "java.lang.Integer", false));
        assertThat(render(spec)).contains("CUSTOMER.CUSTOMER_ID");
    }

    @Test
    void inputType_bodyUsesIntStreamAndDsl() {
        var spec = inputTypeSpec("Customer", "CUSTOMER", "input",
            new LookupInputFieldSpec("customerId", "CUSTOMER_ID", "java.lang.Integer", false));
        String out = render(spec);
        assertThat(out)
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
        assertThat(render(spec)).contains("List<String> ids = (List<String>) arguments.get(\"ids\")");
    }

    @Test
    void flatArgs_scalarArgReadInlineWithCast() {
        var spec = flatSpec("Person", "PERSON",
            new LookupInputFieldSpec("ids", "PERSON_ID", "java.lang.String", true),
            new LookupInputFieldSpec("tenantId", "TENANT_ID", "java.lang.String", false));
        assertThat(render(spec)).contains("(String) arguments.get(\"tenantId\")");
    }

    @Test
    void flatArgs_listArgValueUsesGetI() {
        var spec = flatSpec("Person", "PERSON",
            new LookupInputFieldSpec("ids", "PERSON_ID", "java.lang.String", true));
        assertThat(render(spec)).contains("ids.get(i)");
    }

    @Test
    void flatArgs_sizeFromFirstListArg() {
        var spec = flatSpec("Person", "PERSON",
            new LookupInputFieldSpec("ids", "PERSON_ID", "java.lang.String", true),
            new LookupInputFieldSpec("tenantId", "TENANT_ID", "java.lang.String", false));
        assertThat(render(spec)).contains("ids.size()");
    }

    @Test
    void flatArgs_noInputVarDeclaration() {
        // In flat mode, there should be no local "input" list-of-maps variable
        var spec = flatSpec("Person", "PERSON",
            new LookupInputFieldSpec("ids", "PERSON_ID", "java.lang.String", true));
        assertThat(render(spec)).doesNotContain("Map<String, Object>> input");
    }

    @Test
    void flatArgs_returnTypeCorrect() {
        var spec = flatSpec("Person", "PERSON",
            new LookupInputFieldSpec("ids", "PERSON_ID", "java.lang.String", true),
            new LookupInputFieldSpec("tenantId", "TENANT_ID", "java.lang.String", false));
        assertThat(render(spec)).contains("Record3<Integer, String, String>");
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
