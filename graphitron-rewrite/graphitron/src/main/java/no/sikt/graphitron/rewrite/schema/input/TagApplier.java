package no.sikt.graphitron.rewrite.schema.input;

import graphql.language.Argument;
import graphql.language.Directive;
import graphql.language.DirectiveDefinition;
import graphql.language.DirectiveLocation;
import graphql.language.EnumTypeDefinition;
import graphql.language.EnumTypeExtensionDefinition;
import graphql.language.EnumValueDefinition;
import graphql.language.FieldDefinition;
import graphql.language.InputObjectTypeDefinition;
import graphql.language.InputObjectTypeExtensionDefinition;
import graphql.language.InputValueDefinition;
import graphql.language.InterfaceTypeDefinition;
import graphql.language.InterfaceTypeExtensionDefinition;
import graphql.language.NonNullType;
import graphql.language.ObjectTypeDefinition;
import graphql.language.ObjectTypeExtensionDefinition;
import graphql.language.SDLDefinition;
import graphql.language.SourceLocation;
import graphql.language.StringValue;
import graphql.language.TypeName;
import graphql.language.UnionTypeDefinition;
import graphql.language.UnionTypeExtensionDefinition;
import graphql.schema.idl.TypeDefinitionRegistry;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Applies {@code @tag(name: "<tag>")} to every in-scope element defined in a
 * source whose corresponding {@link SchemaInput} carries a {@code tag}.
 *
 * <p>Emission scope (legacy parity): field definitions, input object fields,
 * enum values, field arguments, and union type declarations themselves. Type
 * declarations (object, interface, enum, input) are never tagged by this
 * applier. This is what {@code <schemaInput tag>} actually applies.
 *
 * <p>Declaration scope (Apollo Federation 2 parity): if the registry does not
 * already declare a {@code @tag} directive (via federation {@code @link} or an
 * explicit declaration), the applier injects a declaration whose {@code on}
 * clause matches federation's stock declaration, not the narrower emission
 * scope above. The two are independent: the auto-injected declaration permits
 * any author-written {@code @tag} use that federation permits (including on
 * scalars, input objects, and type declarations), even though this applier
 * never emits at those locations itself. An element that already declares
 * {@code @tag} explicitly is never double-tagged.
 *
 * <p>Operates on {@link TypeDefinitionRegistry} via a two-pass collect-then-
 * replace pattern: new definitions are collected during the first walk, then
 * swapped in via {@code remove + add} in the second pass. This avoids
 * concurrent modification while still giving {@link SDLDefinition} its
 * immutable-value property.
 */
public final class TagApplier {

    static final String TAG_DIRECTIVE_NAME = "tag";
    static final String TAG_NAME_ARG = "name";

    private TagApplier() {}

    public static void apply(TypeDefinitionRegistry registry, Map<String, SchemaInput> bySource) {
        if (bySource.values().stream().noneMatch(i -> i.tag().isPresent())) {
            return;
        }

        ensureTagDirectiveDeclared(registry);

        var replacements = new ArrayList<Replacement>();
        registry.types().values().forEach(def -> collectReplacement(def, bySource, replacements));
        registry.objectTypeExtensions().values().forEach(list -> list.forEach(def -> collectReplacement(def, bySource, replacements)));
        registry.interfaceTypeExtensions().values().forEach(list -> list.forEach(def -> collectReplacement(def, bySource, replacements)));
        registry.enumTypeExtensions().values().forEach(list -> list.forEach(def -> collectReplacement(def, bySource, replacements)));
        registry.unionTypeExtensions().values().forEach(list -> list.forEach(def -> collectReplacement(def, bySource, replacements)));
        registry.inputObjectTypeExtensions().values().forEach(list -> list.forEach(def -> collectReplacement(def, bySource, replacements)));

        for (var r : replacements) {
            registry.remove(r.oldDef());
            registry.add(r.newDef());
        }
    }

    private record Replacement(SDLDefinition<?> oldDef, SDLDefinition<?> newDef) {}

    private static void collectReplacement(
        SDLDefinition<?> def, Map<String, SchemaInput> bySource, List<Replacement> out
    ) {
        // Order matters: more-specific extension cases must come before their base
        // type arms so the switch dispatches to transformExtension() (which returns
        // the extension type) rather than transform() (which would downgrade an
        // extension to a plain definition and break registry.add's dispatch).
        SDLDefinition<?> transformed = switch (def) {
            case ObjectTypeExtensionDefinition ext -> transformObjectExtension(ext, bySource);
            case ObjectTypeDefinition obj -> transformObject(obj, bySource);
            case InterfaceTypeExtensionDefinition ext -> transformInterfaceExtension(ext, bySource);
            case InterfaceTypeDefinition itf -> transformInterface(itf, bySource);
            case InputObjectTypeExtensionDefinition ext -> transformInputObjectExtension(ext, bySource);
            case InputObjectTypeDefinition inp -> transformInputObject(inp, bySource);
            case EnumTypeExtensionDefinition ext -> transformEnumExtension(ext, bySource);
            case EnumTypeDefinition enm -> transformEnum(enm, bySource);
            case UnionTypeExtensionDefinition ext -> transformUnionExtension(ext, bySource);
            case UnionTypeDefinition uni -> transformUnion(uni, bySource);
            default -> null;
        };
        if (transformed != null) {
            out.add(new Replacement(def, transformed));
        }
    }

    private static ObjectTypeDefinition transformObject(ObjectTypeDefinition node, Map<String, SchemaInput> bySource) {
        var rewritten = rewriteFields(node.getFieldDefinitions(), bySource);
        if (rewritten == null) return null;
        return node.transform(b -> b.fieldDefinitions(rewritten));
    }

    private static ObjectTypeExtensionDefinition transformObjectExtension(ObjectTypeExtensionDefinition node, Map<String, SchemaInput> bySource) {
        var rewritten = rewriteFields(node.getFieldDefinitions(), bySource);
        if (rewritten == null) return null;
        return node.transformExtension(b -> b.fieldDefinitions(rewritten));
    }

    private static InterfaceTypeDefinition transformInterface(InterfaceTypeDefinition node, Map<String, SchemaInput> bySource) {
        var rewritten = rewriteFields(node.getFieldDefinitions(), bySource);
        if (rewritten == null) return null;
        return node.transform(b -> b.definitions(rewritten));
    }

    private static InterfaceTypeExtensionDefinition transformInterfaceExtension(InterfaceTypeExtensionDefinition node, Map<String, SchemaInput> bySource) {
        var rewritten = rewriteFields(node.getFieldDefinitions(), bySource);
        if (rewritten == null) return null;
        return node.transformExtension(b -> b.definitions(rewritten));
    }

    private static InputObjectTypeDefinition transformInputObject(InputObjectTypeDefinition node, Map<String, SchemaInput> bySource) {
        var next = rewriteInputValues(node.getInputValueDefinitions(), bySource);
        return next == null ? null : node.transform(b -> b.inputValueDefinitions(next));
    }

    private static InputObjectTypeExtensionDefinition transformInputObjectExtension(InputObjectTypeExtensionDefinition node, Map<String, SchemaInput> bySource) {
        var next = rewriteInputValues(node.getInputValueDefinitions(), bySource);
        return next == null ? null : node.transformExtension(b -> b.inputValueDefinitions(next));
    }

    private static EnumTypeDefinition transformEnum(EnumTypeDefinition node, Map<String, SchemaInput> bySource) {
        var next = rewriteEnumValues(node.getEnumValueDefinitions(), bySource);
        return next == null ? null : node.transform(b -> b.enumValueDefinitions(next));
    }

    private static EnumTypeExtensionDefinition transformEnumExtension(EnumTypeExtensionDefinition node, Map<String, SchemaInput> bySource) {
        var next = rewriteEnumValues(node.getEnumValueDefinitions(), bySource);
        return next == null ? null : node.transformExtension(b -> b.enumValueDefinitions(next));
    }

    private static UnionTypeDefinition transformUnion(UnionTypeDefinition node, Map<String, SchemaInput> bySource) {
        var tag = tagToApply(node, bySource);
        if (tag == null) return null;
        return node.transform(b -> b.directives(withTag(node.getDirectives(), tag)));
    }

    private static UnionTypeExtensionDefinition transformUnionExtension(UnionTypeExtensionDefinition node, Map<String, SchemaInput> bySource) {
        var tag = tagToApply(node, bySource);
        if (tag == null) return null;
        return node.transformExtension(b -> b.directives(withTag(node.getDirectives(), tag)));
    }

    private static List<InputValueDefinition> rewriteInputValues(List<InputValueDefinition> inputs, Map<String, SchemaInput> bySource) {
        var next = new ArrayList<InputValueDefinition>(inputs.size());
        boolean changed = false;
        for (var input : inputs) {
            var tagged = tagInputValue(input, bySource);
            if (tagged != null) {
                next.add(tagged);
                changed = true;
            } else {
                next.add(input);
            }
        }
        return changed ? next : null;
    }

    private static List<EnumValueDefinition> rewriteEnumValues(List<EnumValueDefinition> values, Map<String, SchemaInput> bySource) {
        var next = new ArrayList<EnumValueDefinition>(values.size());
        boolean changed = false;
        for (var value : values) {
            var tag = tagToApply(value, bySource);
            if (tag != null) {
                next.add(value.transform(b -> b.directives(withTag(value.getDirectives(), tag))));
                changed = true;
            } else {
                next.add(value);
            }
        }
        return changed ? next : null;
    }

    private static List<FieldDefinition> rewriteFields(List<FieldDefinition> fields, Map<String, SchemaInput> bySource) {
        var next = new ArrayList<FieldDefinition>(fields.size());
        boolean changed = false;
        for (var field : fields) {
            var rewrittenArgs = rewriteArguments(field.getInputValueDefinitions(), bySource);
            var withArgs = rewrittenArgs == null ? field : field.transform(b -> b.inputValueDefinitions(rewrittenArgs));
            var tag = tagToApply(withArgs, bySource);
            if (tag != null) {
                next.add(withArgs.transform(b -> b.directives(withTag(withArgs.getDirectives(), tag))));
                changed = true;
            } else if (rewrittenArgs != null) {
                next.add(withArgs);
                changed = true;
            } else {
                next.add(field);
            }
        }
        return changed ? next : null;
    }

    private static List<InputValueDefinition> rewriteArguments(List<InputValueDefinition> args, Map<String, SchemaInput> bySource) {
        if (args.isEmpty()) return null;
        var next = new ArrayList<InputValueDefinition>(args.size());
        boolean changed = false;
        for (var arg : args) {
            var tagged = tagInputValue(arg, bySource);
            if (tagged != null) {
                next.add(tagged);
                changed = true;
            } else {
                next.add(arg);
            }
        }
        return changed ? next : null;
    }

    private static InputValueDefinition tagInputValue(InputValueDefinition node, Map<String, SchemaInput> bySource) {
        var tag = tagToApply(node, bySource);
        if (tag == null) return null;
        return node.transform(b -> b.directives(withTag(node.getDirectives(), tag)));
    }

    private static String tagToApply(graphql.language.DirectivesContainer<?> node, Map<String, SchemaInput> bySource) {
        var input = lookup(node.getSourceLocation(), bySource);
        if (input == null || input.tag().isEmpty()) return null;
        if (hasExplicitTag(node)) return null;
        return input.tag().get();
    }

    private static List<Directive> withTag(List<Directive> existing, String tagValue) {
        var combined = new ArrayList<Directive>(existing.size() + 1);
        combined.addAll(existing);
        combined.add(tagDirective(tagValue));
        return combined;
    }

    private static boolean hasExplicitTag(graphql.language.DirectivesContainer<?> node) {
        return node.getDirectives().stream().anyMatch(d -> TAG_DIRECTIVE_NAME.equals(d.getName()));
    }

    private static SchemaInput lookup(SourceLocation loc, Map<String, SchemaInput> bySource) {
        if (loc == null || loc.getSourceName() == null) return null;
        return bySource.get(loc.getSourceName());
    }

    private static Directive tagDirective(String tagValue) {
        return Directive.newDirective()
            .name(TAG_DIRECTIVE_NAME)
            .argument(Argument.newArgument(TAG_NAME_ARG, new StringValue(tagValue)).build())
            .build();
    }

    private static void ensureTagDirectiveDeclared(TypeDefinitionRegistry registry) {
        if (registry.getDirectiveDefinition(TAG_DIRECTIVE_NAME).isPresent()) return;

        var decl = DirectiveDefinition.newDirectiveDefinition()
            .name(TAG_DIRECTIVE_NAME)
            .repeatable(true)
            .inputValueDefinition(InputValueDefinition.newInputValueDefinition()
                .name(TAG_NAME_ARG)
                .type(NonNullType.newNonNullType(TypeName.newTypeName("String").build()).build())
                .build())
            // Apollo Federation 2 parity: declaration permits the full federation @tag location set,
            // independent of the narrower emission scope this applier walks. See class javadoc.
            .directiveLocation(DirectiveLocation.newDirectiveLocation().name("FIELD_DEFINITION").build())
            .directiveLocation(DirectiveLocation.newDirectiveLocation().name("INTERFACE").build())
            .directiveLocation(DirectiveLocation.newDirectiveLocation().name("OBJECT").build())
            .directiveLocation(DirectiveLocation.newDirectiveLocation().name("UNION").build())
            .directiveLocation(DirectiveLocation.newDirectiveLocation().name("ARGUMENT_DEFINITION").build())
            .directiveLocation(DirectiveLocation.newDirectiveLocation().name("SCALAR").build())
            .directiveLocation(DirectiveLocation.newDirectiveLocation().name("ENUM").build())
            .directiveLocation(DirectiveLocation.newDirectiveLocation().name("ENUM_VALUE").build())
            .directiveLocation(DirectiveLocation.newDirectiveLocation().name("INPUT_OBJECT").build())
            .directiveLocation(DirectiveLocation.newDirectiveLocation().name("INPUT_FIELD_DEFINITION").build())
            .directiveLocation(DirectiveLocation.newDirectiveLocation().name("SCHEMA").build())
            .build();

        Optional<graphql.GraphQLError> error = registry.add(decl);
        if (error.isPresent()) {
            throw new IllegalStateException("Failed to inject @tag directive declaration: " + error.get().getMessage());
        }
    }
}
