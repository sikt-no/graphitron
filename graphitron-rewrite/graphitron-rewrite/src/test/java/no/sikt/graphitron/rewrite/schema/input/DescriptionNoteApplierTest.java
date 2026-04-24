package no.sikt.graphitron.rewrite.schema.input;

import graphql.language.EnumTypeDefinition;
import graphql.language.InputObjectTypeDefinition;
import graphql.language.InterfaceTypeDefinition;
import graphql.language.ObjectTypeDefinition;
import graphql.language.UnionTypeDefinition;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class DescriptionNoteApplierTest {

    @Test
    void elementWithExistingDescriptionGetsConcatenatedNote() {
        var registry = InMemoryRegistry.of(Map.of(
            "t.graphqls", """
                type Foo {
                  "The id."
                  id: ID!
                }
                """
        ));
        var inputs = SchemaInputAttribution.build(List.of(
            new SchemaInput("t.graphqls", Optional.empty(), Optional.of("An enrolment note."))
        ));

        DescriptionNoteApplier.apply(registry, inputs);

        var field = ((ObjectTypeDefinition) registry.getType("Foo").orElseThrow())
            .getFieldDefinitions().getFirst();
        assertThat(field.getDescription().getContent()).isEqualTo("The id.\n\nAn enrolment note.");
    }

    @Test
    void elementWithNoDescriptionGetsNoteAlone() {
        var registry = InMemoryRegistry.of(Map.of(
            "t.graphqls", "type Foo { id: ID! }"
        ));
        var inputs = SchemaInputAttribution.build(List.of(
            new SchemaInput("t.graphqls", Optional.empty(), Optional.of("A note."))
        ));

        DescriptionNoteApplier.apply(registry, inputs);

        var field = ((ObjectTypeDefinition) registry.getType("Foo").orElseThrow())
            .getFieldDefinitions().getFirst();
        assertThat(field.getDescription().getContent()).isEqualTo("A note.");
    }

    @Test
    void elementInEntryWithNoNoteIsUntouched() {
        var registry = InMemoryRegistry.of(Map.of(
            "t.graphqls", """
                type Foo {
                  "Original."
                  id: ID!
                }
                """
        ));
        var inputs = SchemaInputAttribution.build(List.of(
            new SchemaInput("t.graphqls", Optional.of("tag-only"), Optional.empty())
        ));

        DescriptionNoteApplier.apply(registry, inputs);

        var field = ((ObjectTypeDefinition) registry.getType("Foo").orElseThrow())
            .getFieldDefinitions().getFirst();
        assertThat(field.getDescription().getContent()).isEqualTo("Original.");
    }

    @Test
    void multiLineNoteRoundTrips() {
        var registry = InMemoryRegistry.of(Map.of(
            "t.graphqls", "type Foo { id: ID! }"
        ));
        String multiline = "Line one.\nLine two.\nLine three.";
        var inputs = SchemaInputAttribution.build(List.of(
            new SchemaInput("t.graphqls", Optional.empty(), Optional.of(multiline))
        ));

        DescriptionNoteApplier.apply(registry, inputs);

        var field = ((ObjectTypeDefinition) registry.getType("Foo").orElseThrow())
            .getFieldDefinitions().getFirst();
        assertThat(field.getDescription().getContent()).isEqualTo(multiline);
    }

    @Test
    void noteWithBackticksAndEscapeCharactersRoundTrips() {
        var registry = InMemoryRegistry.of(Map.of(
            "t.graphqls", "type Foo { id: ID! }"
        ));
        String tricky = "Use `foo()` and \"quoted\" values.";
        var inputs = SchemaInputAttribution.build(List.of(
            new SchemaInput("t.graphqls", Optional.empty(), Optional.of(tricky))
        ));

        DescriptionNoteApplier.apply(registry, inputs);

        var field = ((ObjectTypeDefinition) registry.getType("Foo").orElseThrow())
            .getFieldDefinitions().getFirst();
        assertThat(field.getDescription().getContent()).isEqualTo(tricky);
    }

    @Test
    void objectTypeDeclarationItselfGetsNote() {
        var registry = InMemoryRegistry.of(Map.of(
            "t.graphqls", """
                "An actor."
                type Actor { id: ID! }
                """
        ));
        var inputs = SchemaInputAttribution.build(List.of(
            new SchemaInput("t.graphqls", Optional.empty(), Optional.of("Part of cinema."))
        ));

        DescriptionNoteApplier.apply(registry, inputs);

        var type = (ObjectTypeDefinition) registry.getType("Actor").orElseThrow();
        assertThat(type.getDescription().getContent()).isEqualTo("An actor.\n\nPart of cinema.");
    }

    @Test
    void interfaceTypeDeclarationItselfGetsNote() {
        var registry = InMemoryRegistry.of(Map.of(
            "t.graphqls", """
                "Has a name."
                interface Named { name: String }
                """
        ));
        var inputs = SchemaInputAttribution.build(List.of(
            new SchemaInput("t.graphqls", Optional.empty(), Optional.of("Common."))
        ));

        DescriptionNoteApplier.apply(registry, inputs);

        var type = (InterfaceTypeDefinition) registry.getType("Named").orElseThrow();
        assertThat(type.getDescription().getContent()).isEqualTo("Has a name.\n\nCommon.");
    }

    @Test
    void enumTypeDeclarationItselfGetsNote() {
        var registry = InMemoryRegistry.of(Map.of(
            "t.graphqls", """
                "A colour."
                enum Color { RED }
                """
        ));
        var inputs = SchemaInputAttribution.build(List.of(
            new SchemaInput("t.graphqls", Optional.empty(), Optional.of("Visible light only."))
        ));

        DescriptionNoteApplier.apply(registry, inputs);

        var type = (EnumTypeDefinition) registry.getType("Color").orElseThrow();
        assertThat(type.getDescription().getContent()).isEqualTo("A colour.\n\nVisible light only.");
    }

    @Test
    void inputObjectTypeDeclarationItselfGetsNote() {
        var registry = InMemoryRegistry.of(Map.of(
            "t.graphqls", """
                "An actor filter."
                input ActorFilter { firstName: String }
                """
        ));
        var inputs = SchemaInputAttribution.build(List.of(
            new SchemaInput("t.graphqls", Optional.empty(), Optional.of("Cinema feature."))
        ));

        DescriptionNoteApplier.apply(registry, inputs);

        var type = (InputObjectTypeDefinition) registry.getType("ActorFilter").orElseThrow();
        assertThat(type.getDescription().getContent()).isEqualTo("An actor filter.\n\nCinema feature.");
    }

    @Test
    void unionTypeDeclarationGetsNote() {
        var registry = InMemoryRegistry.of(Map.of(
            "t.graphqls", """
                type A { id: ID! }
                type B { id: ID! }
                "A or B."
                union AB = A | B
                """
        ));
        var inputs = SchemaInputAttribution.build(List.of(
            new SchemaInput("t.graphqls", Optional.empty(), Optional.of("Widened."))
        ));

        DescriptionNoteApplier.apply(registry, inputs);

        var type = (UnionTypeDefinition) registry.getType("AB").orElseThrow();
        assertThat(type.getDescription().getContent()).isEqualTo("A or B.\n\nWidened.");
    }

    @Test
    void noteAppliesToEveryInScopeMemberInOneWalk() {
        var registry = InMemoryRegistry.of(Map.of(
            "t.graphqls", """
                type Foo {
                  id: ID!
                  name: String
                }
                input FooInput { id: ID! }
                enum Color { RED GREEN }
                """
        ));
        var inputs = SchemaInputAttribution.build(List.of(
            new SchemaInput("t.graphqls", Optional.empty(), Optional.of("note"))
        ));

        DescriptionNoteApplier.apply(registry, inputs);

        var foo = (ObjectTypeDefinition) registry.getType("Foo").orElseThrow();
        assertThat(foo.getDescription().getContent()).isEqualTo("note");
        foo.getFieldDefinitions().forEach(f ->
            assertThat(f.getDescription().getContent()).isEqualTo("note"));

        var fi = (InputObjectTypeDefinition) registry.getType("FooInput").orElseThrow();
        assertThat(fi.getDescription().getContent()).isEqualTo("note");
        fi.getInputValueDefinitions().forEach(v ->
            assertThat(v.getDescription().getContent()).isEqualTo("note"));

        var enm = (EnumTypeDefinition) registry.getType("Color").orElseThrow();
        assertThat(enm.getDescription().getContent()).isEqualTo("note");
        enm.getEnumValueDefinitions().forEach(v ->
            assertThat(v.getDescription().getContent()).isEqualTo("note"));
    }

    @Test
    void extendTypeFieldGainsNoteAndExtensionStaysInExtensionsMap() {
        // Mirror of TagApplier's extend-type test: only the extension source carries
        // a note; assert the extension's field picks up the note AND the extension
        // survives the remove+add round-trip inside objectTypeExtensions().
        var registry = InMemoryRegistry.of(Map.of(
            "base.graphqls", "type Foo { id: ID! }",
            "ext.graphqls", "extend type Foo { bar: String }"
        ));
        var inputs = SchemaInputAttribution.build(List.of(
            new SchemaInput("base.graphqls", Optional.empty(), Optional.empty()),
            new SchemaInput("ext.graphqls", Optional.empty(), Optional.of("Enrolment extension."))
        ));

        DescriptionNoteApplier.apply(registry, inputs);

        var base = (ObjectTypeDefinition) registry.getType("Foo").orElseThrow();
        assertThat(base.getFieldDefinitions().getFirst().getDescription()).isNull();

        var extensions = registry.objectTypeExtensions().get("Foo");
        assertThat(extensions).hasSize(1);
        var extension = extensions.getFirst();
        assertThat(extension.getFieldDefinitions().getFirst().getDescription().getContent())
            .isEqualTo("Enrolment extension.");
    }

    @Test
    void extendInterfaceFieldGainsNoteAndExtensionStaysInExtensionsMap() {
        var registry = InMemoryRegistry.of(Map.of(
            "base.graphqls", "interface Named { id: ID! }",
            "ext.graphqls", "extend interface Named { description: String }"
        ));
        var inputs = SchemaInputAttribution.build(List.of(
            new SchemaInput("base.graphqls", Optional.empty(), Optional.empty()),
            new SchemaInput("ext.graphqls", Optional.empty(), Optional.of("Interface extension note."))
        ));

        DescriptionNoteApplier.apply(registry, inputs);

        var base = (InterfaceTypeDefinition) registry.getType("Named").orElseThrow();
        assertThat(base.getFieldDefinitions().getFirst().getDescription()).isNull();

        var extensions = registry.interfaceTypeExtensions().get("Named");
        assertThat(extensions).hasSize(1);
        assertThat(extensions.getFirst().getFieldDefinitions().getFirst().getDescription().getContent())
            .isEqualTo("Interface extension note.");
    }

    @Test
    void extendInputObjectFieldGainsNoteAndExtensionStaysInExtensionsMap() {
        var registry = InMemoryRegistry.of(Map.of(
            "base.graphqls", "input FooInput { id: ID! }",
            "ext.graphqls", "extend input FooInput { name: String }"
        ));
        var inputs = SchemaInputAttribution.build(List.of(
            new SchemaInput("base.graphqls", Optional.empty(), Optional.empty()),
            new SchemaInput("ext.graphqls", Optional.empty(), Optional.of("Input extension note."))
        ));

        DescriptionNoteApplier.apply(registry, inputs);

        var base = (InputObjectTypeDefinition) registry.getType("FooInput").orElseThrow();
        assertThat(base.getInputValueDefinitions().getFirst().getDescription()).isNull();

        var extensions = registry.inputObjectTypeExtensions().get("FooInput");
        assertThat(extensions).hasSize(1);
        assertThat(extensions.getFirst().getInputValueDefinitions().getFirst().getDescription().getContent())
            .isEqualTo("Input extension note.");
    }

    @Test
    void extendEnumValueGainsNoteAndExtensionStaysInExtensionsMap() {
        var registry = InMemoryRegistry.of(Map.of(
            "base.graphqls", "enum Color { RED }",
            "ext.graphqls", "extend enum Color { GREEN BLUE }"
        ));
        var inputs = SchemaInputAttribution.build(List.of(
            new SchemaInput("base.graphqls", Optional.empty(), Optional.empty()),
            new SchemaInput("ext.graphqls", Optional.empty(), Optional.of("Enum extension note."))
        ));

        DescriptionNoteApplier.apply(registry, inputs);

        var base = (EnumTypeDefinition) registry.getType("Color").orElseThrow();
        assertThat(base.getEnumValueDefinitions().getFirst().getDescription()).isNull();

        var extensions = registry.enumTypeExtensions().get("Color");
        assertThat(extensions).hasSize(1);
        var extValues = extensions.getFirst().getEnumValueDefinitions();
        assertThat(extValues).hasSize(2);
        assertThat(extValues.getFirst().getDescription().getContent()).isEqualTo("Enum extension note.");
        assertThat(extValues.get(1).getDescription().getContent()).isEqualTo("Enum extension note.");
    }

    @Test
    void extendUnionDeclarationGainsNoteAndStaysInExtensionsMap() {
        var registry = InMemoryRegistry.of(Map.of(
            "base.graphqls", """
                type A { id: ID! }
                type B { id: ID! }
                union AB = A | B
                """,
            "ext.graphqls", """
                type C { id: ID! }
                extend union AB = C
                """
        ));
        var inputs = SchemaInputAttribution.build(List.of(
            new SchemaInput("base.graphqls", Optional.empty(), Optional.empty()),
            new SchemaInput("ext.graphqls", Optional.empty(), Optional.of("Union extension note."))
        ));

        DescriptionNoteApplier.apply(registry, inputs);

        var base = (UnionTypeDefinition) registry.getType("AB").orElseThrow();
        assertThat(base.getDescription()).isNull();

        var extensions = registry.unionTypeExtensions().get("AB");
        assertThat(extensions).hasSize(1);
        assertThat(extensions.getFirst().getDescription().getContent()).isEqualTo("Union extension note.");
    }

    @Test
    void existingDescriptionIsStrippedBeforeConcat() {
        // Leading / trailing whitespace on the existing description is dropped
        // before the "\n\n<note>" append; keeps the output deterministic.
        var registry = InMemoryRegistry.of(Map.of(
            "t.graphqls", """
                type Foo {
                  "   Padded.   "
                  id: ID!
                }
                """
        ));
        var inputs = SchemaInputAttribution.build(List.of(
            new SchemaInput("t.graphqls", Optional.empty(), Optional.of("   note   "))
        ));

        DescriptionNoteApplier.apply(registry, inputs);

        var field = ((ObjectTypeDefinition) registry.getType("Foo").orElseThrow())
            .getFieldDefinitions().getFirst();
        assertThat(field.getDescription().getContent()).isEqualTo("Padded.\n\nnote");
    }
}
