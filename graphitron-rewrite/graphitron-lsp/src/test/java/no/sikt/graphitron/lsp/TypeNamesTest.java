package no.sikt.graphitron.lsp;

import no.sikt.graphitron.lsp.parsing.TypeNames;
import no.sikt.graphitron.lsp.state.WorkspaceFile;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests the tree-sitter-graphql node-name assumptions in
 * {@link TypeNames#extract}. If the grammar uses different node names
 * for any of the declaration / reference shapes, these tests catch it.
 */
class TypeNamesTest {

    @Test
    void extractsObjectInterfaceUnionEnumInputAndScalarDeclarations() {
        String source = """
            type Foo { x: Int }
            interface Node { id: ID }
            union FooOrBar = Foo | Bar
            enum Color { RED GREEN BLUE }
            input FilterInput { name: String }
            scalar DateTime

            type Bar { d: DateTime }
            """;
        var file = new WorkspaceFile(1, source);

        assertThat(file.declaredTypes())
            .contains("Foo", "Node", "FooOrBar", "Color", "FilterInput", "DateTime", "Bar");
    }

    @Test
    void dependsOnDeclarationsExcludesBuiltinScalarsAndOwnDeclarations() {
        String source = """
            type Foo { bar: Bar, qty: Int, name: String }
            """;
        var file = new WorkspaceFile(1, source);

        // Foo declares Foo; references Bar, Int, String. Built-ins drop;
        // self-decl drops; Bar remains as the only outside-this-file dep.
        assertThat(file.declaredTypes()).containsOnly("Foo");
        assertThat(file.dependsOnDeclarations()).containsOnly("Bar");
    }

    @Test
    void dependsOnRefreshesAfterEdit() {
        String source = """
            type Foo { bar: Bar }
            """;
        var file = new WorkspaceFile(1, source);
        assertThat(file.dependsOnDeclarations()).containsOnly("Bar");

        // Wholesale replace to swap the dependency.
        file.replaceContent(2, """
            type Foo { baz: Baz }
            """);
        assertThat(file.dependsOnDeclarations()).containsOnly("Baz");
    }
}
