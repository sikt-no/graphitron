package no.sikt.graphitron.rewrite.generators.schema;

import no.sikt.graphitron.javapoet.TypeSpec;
import no.sikt.graphitron.rewrite.TestSchemaHelper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import no.sikt.graphitron.rewrite.test.tier.UnitTier;

@UnitTier
class DefaultValueEmissionTest {

    @Test
    void fieldArgument_defaultInt_emitsDefaultValueProgrammatic() {
        var queryBody = findObjectBody("""
            type Query {
              films(first: Int = 100): [String]
            }
            """, "QueryType");
        assertThat(queryBody)
            .contains(".name(\"first\")")
            .contains(".defaultValueProgrammatic(100)");
    }

    @Test
    void fieldArgument_defaultString_emitsQuotedDefault() {
        var queryBody = findObjectBody("""
            type Query {
              search(term: String = "hello"): [String]
            }
            """, "QueryType");
        assertThat(queryBody).contains(".defaultValueProgrammatic(\"hello\")");
    }

    @Test
    void fieldArgument_defaultList_emitsListOf() {
        var queryBody = findObjectBody("""
            type Query {
              tags(names: [String!] = ["a", "b"]): [String]
            }
            """, "QueryType");
        assertThat(queryBody).contains(".defaultValueProgrammatic(java.util.List.of(\"a\", \"b\"))");
    }

    @Test
    void fieldArgument_withoutDefault_omitsDefaultValueCall() {
        var queryBody = findObjectBody("""
            type Query {
              films(first: Int): [String]
            }
            """, "QueryType");
        assertThat(queryBody).doesNotContain(".defaultValueProgrammatic(");
    }

    @Test
    void inputField_defaultBoolean_emitsDefaultValueProgrammatic() {
        var body = findInputBody("""
            type Query { x: String }
            input FilterInput { active: Boolean = true }
            """, "FilterInputType");
        assertThat(body).contains(".defaultValueProgrammatic(true)");
    }

    @Test
    void inputField_withoutDefault_omitsDefaultValueCall() {
        var body = findInputBody("""
            type Query { x: String }
            input FilterInput { keyword: String }
            """, "FilterInputType");
        assertThat(body).doesNotContain(".defaultValueProgrammatic(");
    }

    private static String findObjectBody(String sdl, String typeName) {
        var bundle = TestSchemaHelper.buildBundle(sdl);
        return findBody(ObjectTypeGenerator.generate(bundle.model(), bundle.assembled()), typeName);
    }

    private static String findInputBody(String sdl, String typeName) {
        var bundle = TestSchemaHelper.buildBundle(sdl);
        return findBody(InputTypeGenerator.generate(bundle.model()), typeName);
    }

    private static String findBody(List<TypeSpec> specs, String typeName) {
        return specs.stream()
            .filter(s -> s.name().equals(typeName))
            .findFirst()
            .orElseThrow(() -> new AssertionError("no " + typeName + " in " + specs))
            .methodSpecs().get(0).code().toString();
    }
}
