package no.sikt.graphitron.lsp;

import no.sikt.graphitron.lsp.facts.DirectiveSurface;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What a graph's capture says its directive vocabulary is, read back.
 *
 * <p>The fixture declares directives of an author's own beside graphitron's, because the whole point
 * of reading the shape out of the store is that the two are the same population. The language server
 * used to parse graphitron's bundled definitions itself and hold them in a graphql-java registry, and
 * an author's directives were then a separate and much thinner thing: argument names and no
 * input-object shape, so nothing nested inside one could be descended into. Here both are rows.
 */
class DirectiveSurfaceTest {

    private static final String SDL = """
        type Query { placeholder: Int }

        directive @demo(name: String!, refs: [DemoRef!], count: Int) on OBJECT

        directive @bare on OBJECT

        input DemoRef {
            className: String
            nested: DemoNested
        }

        input DemoNested {
            deep: String
        }

        type NotAnInput {
            field: String
        }
        """;

    @TempDir
    static Path tmp;

    private static StoreFixture store;
    private static DirectiveSurface surface;

    @BeforeAll
    static void capture() {
        store = StoreFixture.of(tmp, SDL);
        surface = DirectiveSurface.load(store.handle());
    }

    @AfterAll
    static void closeStore() {
        store.close();
    }

    @Test
    void anAuthorsOwnDirectivesAreDeclaredBesideGraphitronsBundledOnes() {
        assertThat(surface.declaresDirective("demo")).isTrue();
        assertThat(surface.declaresDirective("bare")).isTrue();
        assertThat(surface.declaresDirective("table")).isTrue();
        assertThat(surface.declaresDirective("neverWritten")).isFalse();
    }

    @Test
    void aDirectiveWithNoArgumentsIsStillDeclared() {
        // Its arguments are its only rows in the argument relation, and it has none, so a surface
        // built from that relation alone would lose the directive entirely.
        assertThat(surface.declaresDirective("bare")).isTrue();
        assertThat(surface.declaresArgument("bare", "anything")).isFalse();
    }

    @Test
    void anArgumentsTypeIsTheOneItsExpressionBottomsOutIn() {
        // Written '[DemoRef!]', so the wrapping is what the store decoded away.
        assertThat(surface.argumentNamedType("demo", "refs")).contains("DemoRef");
        assertThat(surface.argumentNamedType("demo", "name")).contains("String");
        assertThat(surface.argumentNamedType("demo", "notAnArgument")).isEmpty();
        assertThat(surface.argumentNamedType("neverWritten", "refs")).isEmpty();
    }

    @Test
    void inputObjectFieldsCarryTheirOwnNamedTypeSoADescentCanContinue() {
        assertThat(surface.inputFieldNamedType("DemoRef", "nested")).contains("DemoNested");
        assertThat(surface.inputFieldNamedType("DemoNested", "deep")).contains("String");
        assertThat(surface.inputFieldNamedType("DemoRef", "notAField")).isEmpty();
    }

    /**
     * The guard graphql-java used to give for free by refusing to hand back an input-object
     * definition for an output type. Fields of both kinds sit in one relation under one shape, so only
     * the join to the type's kind tells them apart, and without it a nested literal would descend into
     * an object type that no literal can ever be.
     */
    @Test
    void anOutputTypesFieldsAreNotInputFields() {
        assertThat(surface.declaresInputObject("NotAnInput")).isFalse();
        assertThat(surface.inputFieldNamedType("NotAnInput", "field")).isEmpty();
        assertThat(surface.declaresInputObject("DemoRef")).isTrue();
    }

    @Test
    void anEmptySurfaceDeclaresNothing() {
        var empty = DirectiveSurface.empty();
        assertThat(empty.declaresDirective("table")).isFalse();
        assertThat(empty.declaresInputObject("DemoRef")).isFalse();
        assertThat(empty.argumentNamedType("demo", "name")).isEmpty();
        assertThat(empty.inputFieldNamedType("DemoRef", "className")).isEmpty();
    }
}
