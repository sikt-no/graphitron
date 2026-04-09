package no.sikt.graphitron.rewrite.generators.fields;

import no.sikt.graphitron.javapoet.MethodSpec;
import no.sikt.graphitron.javapoet.TypeSpec;
import no.sikt.graphitron.rewrite.field.ChildField;
import no.sikt.graphitron.rewrite.field.ColumnRef;
import no.sikt.graphitron.rewrite.field.GraphitronField;
import org.junit.jupiter.api.Test;

import javax.lang.model.element.Modifier;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class FieldsCodeGeneratorTest {

    private static final FieldsCodeGenerator GEN = new FieldsCodeGenerator();

    private static GraphitronField field(String name) {
        return new ChildField.ColumnField("Film", name, null, name,
            new ColumnRef.ResolvedColumn("COL", "java.lang.String"), false);
    }

    private static TypeSpec spec(String typeName, List<String> fieldNames) {
        return GEN.generate(typeName, fieldNames.stream().map(FieldsCodeGeneratorTest::field).toList());
    }

    private static MethodSpec method(TypeSpec spec, String name) {
        return spec.methodSpecs().stream()
            .filter(m -> m.name().equals(name))
            .findFirst()
            .orElseThrow(() -> new AssertionError("Method not found: " + name));
    }

    // ===== Class structure =====

    @Test
    void generate_classNameIsTypeNamePlusFields() {
        assertThat(spec("Film", List.of()).name()).isEqualTo("FilmFields");
    }

    @Test
    void generate_classIsPublic() {
        assertThat(spec("Film", List.of()).modifiers()).contains(Modifier.PUBLIC);
    }

    // ===== Per-field stub methods =====

    @Test
    void generate_fieldMethodIsPresent() {
        assertThat(spec("Film", List.of("title")).methodSpecs())
            .extracting(MethodSpec::name)
            .contains("title");
    }

    @Test
    void generate_fieldMethodIsPublicStatic() {
        var m = method(spec("Film", List.of("title")), "title");
        assertThat(m.modifiers()).containsExactlyInAnyOrder(Modifier.PUBLIC, Modifier.STATIC);
    }

    @Test
    void generate_fieldMethodReturnsObject() {
        var m = method(spec("Film", List.of("title")), "title");
        assertThat(m.returnType().toString()).isEqualTo("java.lang.Object");
    }

    @Test
    void generate_fieldMethodTakesDataFetchingEnvironment() {
        var m = method(spec("Film", List.of("title")), "title");
        assertThat(m.parameters()).extracting(p -> p.type().toString())
            .containsExactly("graphql.schema.DataFetchingEnvironment");
        assertThat(m.parameters()).extracting(p -> p.name())
            .containsExactly("env");
    }

    @Test
    void generate_fieldMethodThrowsUnsupportedOperationException() {
        var m = method(spec("Film", List.of("title")), "title");
        assertThat(m.code().toString()).contains("UnsupportedOperationException()");
    }

    @Test
    void generate_multipleFields_allPresent() {
        var s = spec("Film", List.of("title", "releaseYear"));
        assertThat(s.methodSpecs()).extracting(MethodSpec::name).contains("title", "releaseYear");
    }

    // ===== wiring() method =====

    @Test
    void generate_wiringMethodIsPresent() {
        assertThat(spec("Film", List.of()).methodSpecs())
            .extracting(MethodSpec::name)
            .contains("wiring");
    }

    @Test
    void generate_wiringMethodIsPublicStatic() {
        var w = method(spec("Film", List.of()), "wiring");
        assertThat(w.modifiers()).containsExactlyInAnyOrder(Modifier.PUBLIC, Modifier.STATIC);
    }

    @Test
    void generate_wiringMethodReturnsTypeRuntimeWiringBuilder() {
        var w = method(spec("Film", List.of()), "wiring");
        assertThat(w.returnType().toString())
            .isEqualTo("graphql.schema.idl.TypeRuntimeWiring.Builder");
    }

    @Test
    void generate_wiringMethod_containsTypeName() {
        var w = method(spec("Film", List.of()), "wiring");
        assertThat(w.code().toString()).contains("newTypeWiring(\"Film\")");
    }

    @Test
    void generate_wiringMethod_usesMethodReference() {
        var w = method(spec("Film", List.of("title")), "wiring");
        assertThat(w.code().toString()).contains("FilmFields::title");
    }

    @Test
    void generate_wiringMethod_registersFieldByName() {
        var w = method(spec("Film", List.of("title")), "wiring");
        assertThat(w.code().toString()).contains("dataFetcher(\"title\"");
    }

    @Test
    void generate_wiringMethod_noFields_noDataFetchers() {
        var w = method(spec("Film", List.of()), "wiring");
        assertThat(w.code().toString()).doesNotContain("dataFetcher(");
    }

    @Test
    void generate_wiringMethod_multipleFields_allRegistered() {
        var w = method(spec("Film", List.of("title", "releaseYear")), "wiring");
        assertThat(w.code().toString()).contains("dataFetcher(\"title\"");
        assertThat(w.code().toString()).contains("dataFetcher(\"releaseYear\"");
    }
}
