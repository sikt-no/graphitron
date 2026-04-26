package no.sikt.graphitron.rewrite.schema.input;

import graphql.language.Argument;
import graphql.language.Directive;
import graphql.language.DirectivesContainer;
import graphql.language.EnumTypeDefinition;
import graphql.language.InputObjectTypeDefinition;
import graphql.language.InterfaceTypeDefinition;
import graphql.language.NonNullType;
import graphql.language.ObjectTypeDefinition;
import graphql.language.StringValue;
import graphql.language.TypeName;
import graphql.language.UnionTypeDefinition;
import graphql.schema.idl.TypeDefinitionRegistry;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class TagApplierTest {

    @Test
    void fieldInTaggedFileGainsTagDirective() {
        var registry = InMemoryRegistry.of(Map.of(
            "tagged.graphqls", "type Foo { id: ID! }"
        ));
        var inputs = SchemaInputAttribution.build(List.of(
            new SchemaInput("tagged.graphqls", Optional.of("enrollment"), Optional.empty())
        ));

        TagApplier.apply(registry, inputs);

        var field = ((ObjectTypeDefinition) registry.getType("Foo").orElseThrow())
            .getFieldDefinitions().getFirst();
        assertThat(tagValue(field)).isEqualTo("enrollment");
    }

    @Test
    void fieldWithExplicitTagIsNotDoubleTagged() {
        var registry = InMemoryRegistry.of(Map.of(
            "tagged.graphqls", """
                directive @tag(name: String!) repeatable on FIELD_DEFINITION
                type Foo { id: ID! @tag(name: "explicit") }
                """
        ));
        var inputs = SchemaInputAttribution.build(List.of(
            new SchemaInput("tagged.graphqls", Optional.of("auto"), Optional.empty())
        ));

        TagApplier.apply(registry, inputs);

        var field = ((ObjectTypeDefinition) registry.getType("Foo").orElseThrow())
            .getFieldDefinitions().getFirst();
        assertThat(field.getDirectives()).hasSize(1);
        assertThat(tagValue(field)).isEqualTo("explicit");
    }

    @Test
    void fieldInUntaggedEntryIsUntouched() {
        var registry = InMemoryRegistry.of(Map.of(
            "plain.graphqls", "type Foo { id: ID! }"
        ));
        var inputs = SchemaInputAttribution.build(List.of(
            new SchemaInput("plain.graphqls", Optional.empty(), Optional.of("note"))
        ));

        TagApplier.apply(registry, inputs);

        var field = ((ObjectTypeDefinition) registry.getType("Foo").orElseThrow())
            .getFieldDefinitions().getFirst();
        assertThat(field.getDirectives()).isEmpty();
    }

    @Test
    void inputObjectFieldGainsTag() {
        var registry = InMemoryRegistry.of(Map.of(
            "t.graphqls", "input FooInput { id: ID! }"
        ));
        var inputs = SchemaInputAttribution.build(List.of(
            new SchemaInput("t.graphqls", Optional.of("x"), Optional.empty())
        ));

        TagApplier.apply(registry, inputs);

        var field = ((InputObjectTypeDefinition) registry.getType("FooInput").orElseThrow())
            .getInputValueDefinitions().getFirst();
        assertThat(tagValue(field)).isEqualTo("x");
    }

    @Test
    void enumValueGainsTag() {
        var registry = InMemoryRegistry.of(Map.of(
            "t.graphqls", "enum Color { RED GREEN }"
        ));
        var inputs = SchemaInputAttribution.build(List.of(
            new SchemaInput("t.graphqls", Optional.of("x"), Optional.empty())
        ));

        TagApplier.apply(registry, inputs);

        var values = ((EnumTypeDefinition) registry.getType("Color").orElseThrow())
            .getEnumValueDefinitions();
        assertThat(tagValue(values.getFirst())).isEqualTo("x");
        assertThat(tagValue(values.get(1))).isEqualTo("x");
    }

    @Test
    void fieldArgumentGainsTag() {
        var registry = InMemoryRegistry.of(Map.of(
            "t.graphqls", "type Foo { item(id: ID!): String }"
        ));
        var inputs = SchemaInputAttribution.build(List.of(
            new SchemaInput("t.graphqls", Optional.of("x"), Optional.empty())
        ));

        TagApplier.apply(registry, inputs);

        var field = ((ObjectTypeDefinition) registry.getType("Foo").orElseThrow())
            .getFieldDefinitions().getFirst();
        var arg = field.getInputValueDefinitions().getFirst();
        assertThat(tagValue(arg)).isEqualTo("x");
    }

    @Test
    void unionTypeDeclarationGainsTag() {
        var registry = InMemoryRegistry.of(Map.of(
            "t.graphqls", """
                type A { id: ID! }
                type B { id: ID! }
                union AB = A | B
                """
        ));
        var inputs = SchemaInputAttribution.build(List.of(
            new SchemaInput("t.graphqls", Optional.of("ab-tag"), Optional.empty())
        ));

        TagApplier.apply(registry, inputs);

        var union = (UnionTypeDefinition) registry.getType("AB").orElseThrow();
        assertThat(tagValue(union)).isEqualTo("ab-tag");
    }

    @Test
    void interfaceFieldGainsTag() {
        var registry = InMemoryRegistry.of(Map.of(
            "t.graphqls", "interface Named { name: String }"
        ));
        var inputs = SchemaInputAttribution.build(List.of(
            new SchemaInput("t.graphqls", Optional.of("iface-tag"), Optional.empty())
        ));

        TagApplier.apply(registry, inputs);

        var field = ((InterfaceTypeDefinition) registry.getType("Named").orElseThrow())
            .getFieldDefinitions().getFirst();
        assertThat(tagValue(field)).isEqualTo("iface-tag");
    }

    @Test
    void typeDeclarationsThemselvesAreNotTagged() {
        var registry = InMemoryRegistry.of(Map.of(
            "t.graphqls", """
                type Foo { id: ID! }
                interface Bar { id: ID! }
                enum Baz { A }
                input Qux { id: ID! }
                """
        ));
        var inputs = SchemaInputAttribution.build(List.of(
            new SchemaInput("t.graphqls", Optional.of("x"), Optional.empty())
        ));

        TagApplier.apply(registry, inputs);

        assertThat(registry.getType("Foo").orElseThrow().getDirectives()).isEmpty();
        assertThat(registry.getType("Bar").orElseThrow().getDirectives()).isEmpty();
        assertThat(registry.getType("Baz").orElseThrow().getDirectives()).isEmpty();
        assertThat(registry.getType("Qux").orElseThrow().getDirectives()).isEmpty();
    }

    @Test
    void tagDirectiveAutoInjectedWhenAbsent() {
        var registry = InMemoryRegistry.of(Map.of(
            "t.graphqls", "type Foo { id: ID! }"
        ));
        assertThat(registry.getDirectiveDefinition("tag")).isEmpty();

        var inputs = SchemaInputAttribution.build(List.of(
            new SchemaInput("t.graphqls", Optional.of("x"), Optional.empty())
        ));
        TagApplier.apply(registry, inputs);

        var decl = registry.getDirectiveDefinition("tag").orElseThrow();
        assertThat(decl.isRepeatable()).isTrue();
        assertThat(decl.getDirectiveLocations()).extracting(l -> l.getName())
            .containsExactlyInAnyOrder(
                "FIELD_DEFINITION", "INPUT_FIELD_DEFINITION", "ENUM_VALUE",
                "ARGUMENT_DEFINITION", "UNION"
            );
        assertThat(decl.getInputValueDefinitions()).hasSize(1);
        var arg = decl.getInputValueDefinitions().getFirst();
        assertThat(arg.getName()).isEqualTo("name");
        assertThat(arg.getType()).isInstanceOfSatisfying(NonNullType.class, nn ->
            assertThat(((TypeName) nn.getType()).getName()).isEqualTo("String")
        );
    }

    @Test
    void existingTagDirectiveDeclarationIsPreserved() {
        // User hand-declares @tag with a narrower location set; applier respects it.
        var registry = InMemoryRegistry.of(Map.of(
            "t.graphqls", """
                directive @tag(name: String!) on FIELD_DEFINITION
                type Foo { id: ID! }
                """
        ));
        var original = registry.getDirectiveDefinition("tag").orElseThrow();

        var inputs = SchemaInputAttribution.build(List.of(
            new SchemaInput("t.graphqls", Optional.of("x"), Optional.empty())
        ));
        TagApplier.apply(registry, inputs);

        var after = registry.getDirectiveDefinition("tag").orElseThrow();
        assertThat(after).isSameAs(original);
    }

    @Test
    void noTagDirectiveInjectedWhenNoEntryCarriesATag() {
        var registry = InMemoryRegistry.of(Map.of(
            "t.graphqls", "type Foo { id: ID! }"
        ));
        var inputs = SchemaInputAttribution.build(List.of(
            new SchemaInput("t.graphqls", Optional.empty(), Optional.of("note only"))
        ));

        TagApplier.apply(registry, inputs);

        assertThat(registry.getDirectiveDefinition("tag")).isEmpty();
    }

    @Test
    void extendTypeFieldGainsTagAndStaysInExtensionsMap() {
        // Base type in one source, extension with new fields in another; only the
        // extension source carries a tag. Asserts both that the extension's field
        // picks up @tag and that the remove+add round-trip preserves the extension's
        // identity (it stays in objectTypeExtensions(), not moved into types()).
        var registry = InMemoryRegistry.of(Map.of(
            "base.graphqls", "type Foo { id: ID! }",
            "ext.graphqls", "extend type Foo { bar: String }"
        ));
        var inputs = SchemaInputAttribution.build(List.of(
            new SchemaInput("base.graphqls", Optional.empty(), Optional.empty()),
            new SchemaInput("ext.graphqls", Optional.of("enrolment"), Optional.empty())
        ));

        TagApplier.apply(registry, inputs);

        // Base field is untouched (untagged source).
        var base = (ObjectTypeDefinition) registry.getType("Foo").orElseThrow();
        assertThat(base.getFieldDefinitions().getFirst().getDirectives()).isEmpty();

        // Extension's new field carries the tag, and the extension is still in
        // the extensions map with the replacement in place.
        var extensions = registry.objectTypeExtensions().get("Foo");
        assertThat(extensions).hasSize(1);
        var extension = extensions.getFirst();
        assertThat(extension.getFieldDefinitions()).hasSize(1);
        assertThat(tagValue(extension.getFieldDefinitions().getFirst())).isEqualTo("enrolment");
    }

    @Test
    void extendInterfaceFieldGainsTagAndStaysInExtensionsMap() {
        var registry = InMemoryRegistry.of(Map.of(
            "base.graphqls", "interface Named { id: ID! }",
            "ext.graphqls", "extend interface Named { description: String }"
        ));
        var inputs = SchemaInputAttribution.build(List.of(
            new SchemaInput("base.graphqls", Optional.empty(), Optional.empty()),
            new SchemaInput("ext.graphqls", Optional.of("iface-ext"), Optional.empty())
        ));

        TagApplier.apply(registry, inputs);

        var base = (InterfaceTypeDefinition) registry.getType("Named").orElseThrow();
        assertThat(base.getFieldDefinitions().getFirst().getDirectives()).isEmpty();

        var extensions = registry.interfaceTypeExtensions().get("Named");
        assertThat(extensions).hasSize(1);
        var extension = extensions.getFirst();
        assertThat(extension.getFieldDefinitions()).hasSize(1);
        assertThat(tagValue(extension.getFieldDefinitions().getFirst())).isEqualTo("iface-ext");
    }

    @Test
    void extendInputObjectFieldGainsTagAndStaysInExtensionsMap() {
        var registry = InMemoryRegistry.of(Map.of(
            "base.graphqls", "input FooInput { id: ID! }",
            "ext.graphqls", "extend input FooInput { name: String }"
        ));
        var inputs = SchemaInputAttribution.build(List.of(
            new SchemaInput("base.graphqls", Optional.empty(), Optional.empty()),
            new SchemaInput("ext.graphqls", Optional.of("input-ext"), Optional.empty())
        ));

        TagApplier.apply(registry, inputs);

        var base = (InputObjectTypeDefinition) registry.getType("FooInput").orElseThrow();
        assertThat(base.getInputValueDefinitions().getFirst().getDirectives()).isEmpty();

        var extensions = registry.inputObjectTypeExtensions().get("FooInput");
        assertThat(extensions).hasSize(1);
        var extension = extensions.getFirst();
        assertThat(extension.getInputValueDefinitions()).hasSize(1);
        assertThat(tagValue(extension.getInputValueDefinitions().getFirst())).isEqualTo("input-ext");
    }

    @Test
    void extendEnumValueGainsTagAndStaysInExtensionsMap() {
        var registry = InMemoryRegistry.of(Map.of(
            "base.graphqls", "enum Color { RED }",
            "ext.graphqls", "extend enum Color { GREEN BLUE }"
        ));
        var inputs = SchemaInputAttribution.build(List.of(
            new SchemaInput("base.graphqls", Optional.empty(), Optional.empty()),
            new SchemaInput("ext.graphqls", Optional.of("enum-ext"), Optional.empty())
        ));

        TagApplier.apply(registry, inputs);

        var base = (EnumTypeDefinition) registry.getType("Color").orElseThrow();
        assertThat(base.getEnumValueDefinitions().getFirst().getDirectives()).isEmpty();

        var extensions = registry.enumTypeExtensions().get("Color");
        assertThat(extensions).hasSize(1);
        var extension = extensions.getFirst();
        assertThat(extension.getEnumValueDefinitions()).hasSize(2);
        assertThat(tagValue(extension.getEnumValueDefinitions().getFirst())).isEqualTo("enum-ext");
        assertThat(tagValue(extension.getEnumValueDefinitions().get(1))).isEqualTo("enum-ext");
    }

    @Test
    void extendUnionDeclarationGainsTagAndStaysInExtensionsMap() {
        // Union extension can add members (= C) or directives; tagging the extension
        // itself is the in-scope operation for TagApplier. We add a member so the
        // extension has content beyond the @tag the applier injects.
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
            new SchemaInput("ext.graphqls", Optional.of("union-ext"), Optional.empty())
        ));

        TagApplier.apply(registry, inputs);

        var base = (UnionTypeDefinition) registry.getType("AB").orElseThrow();
        assertThat(base.getDirectives()).isEmpty();

        var extensions = registry.unionTypeExtensions().get("AB");
        assertThat(extensions).hasSize(1);
        assertThat(tagValue(extensions.getFirst())).isEqualTo("union-ext");
    }

    @Test
    void tagValueWithQuotesAndUnicodeRoundTrips() {
        var registry = InMemoryRegistry.of(Map.of(
            "t.graphqls", "type Foo { id: ID! }"
        ));
        String tricky = "naïve-\"quoted\"-\\backslash";
        var inputs = SchemaInputAttribution.build(List.of(
            new SchemaInput("t.graphqls", Optional.of(tricky), Optional.empty())
        ));

        TagApplier.apply(registry, inputs);

        var field = ((ObjectTypeDefinition) registry.getType("Foo").orElseThrow())
            .getFieldDefinitions().getFirst();
        assertThat(tagValue(field)).isEqualTo(tricky);
    }

    private static String tagValue(DirectivesContainer<?> node) {
        var tags = node.getDirectives().stream().filter(d -> "tag".equals(d.getName())).toList();
        assertThat(tags).hasSize(1);
        Directive d = tags.getFirst();
        Argument arg = d.getArgument("name");
        assertThat(arg).isNotNull();
        assertThat(arg.getValue()).isInstanceOf(StringValue.class);
        return ((StringValue) arg.getValue()).getValue();
    }
}
