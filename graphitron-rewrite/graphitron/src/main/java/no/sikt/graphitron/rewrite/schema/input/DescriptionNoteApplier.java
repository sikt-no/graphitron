package no.sikt.graphitron.rewrite.schema.input;

import graphql.language.Description;
import graphql.language.EnumTypeDefinition;
import graphql.language.EnumTypeExtensionDefinition;
import graphql.language.EnumValueDefinition;
import graphql.language.FieldDefinition;
import graphql.language.InputObjectTypeDefinition;
import graphql.language.InputObjectTypeExtensionDefinition;
import graphql.language.InputValueDefinition;
import graphql.language.InterfaceTypeDefinition;
import graphql.language.InterfaceTypeExtensionDefinition;
import graphql.language.ObjectTypeDefinition;
import graphql.language.ObjectTypeExtensionDefinition;
import graphql.language.SDLDefinition;
import graphql.language.SourceLocation;
import graphql.language.UnionTypeDefinition;
import graphql.language.UnionTypeExtensionDefinition;
import graphql.schema.idl.TypeDefinitionRegistry;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Appends a description note to every in-scope element defined in a source
 * whose corresponding {@link SchemaInput} carries a {@code descriptionNote}.
 *
 * <p>Element scope (widened past legacy): everything {@link TagApplier}
 * touches plus the type declarations themselves (object, interface, enum,
 * input). Notes are documentation, and the type declaration is the most
 * discoverable place a reader looks.
 *
 * <p>The note is appended with a blank-line separator when the element
 * already has a description, or used alone when it does not. Both the
 * existing description and the note are {@link String#strip() stripped}
 * before concatenation. The separator is a literal {@code "\n\n"} so the
 * output is identical across build hosts, unlike the legacy behaviour that
 * used {@link System#lineSeparator()}.
 *
 * <p>Like {@link TagApplier}, uses a two-pass collect-then-replace pattern
 * against the {@link TypeDefinitionRegistry}.
 */
public final class DescriptionNoteApplier {

    private DescriptionNoteApplier() {}

    public static void apply(TypeDefinitionRegistry registry, Map<String, SchemaInput> bySource) {
        if (bySource.values().stream().noneMatch(i -> i.descriptionNote().isPresent())) {
            return;
        }

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

    private record ObjectChanges(List<FieldDefinition> rewrittenFields, Description newDescription) {
        boolean any() { return rewrittenFields != null || newDescription != null; }
    }

    private static ObjectChanges computeObjectChanges(
        List<FieldDefinition> fields, graphql.language.AbstractNode<?> node, Map<String, SchemaInput> bySource
    ) {
        return new ObjectChanges(rewriteFields(fields, bySource), descriptionToApply(node, bySource));
    }

    private static ObjectTypeDefinition transformObject(ObjectTypeDefinition node, Map<String, SchemaInput> bySource) {
        var ch = computeObjectChanges(node.getFieldDefinitions(), node, bySource);
        if (!ch.any()) return null;
        return node.transform(b -> {
            if (ch.rewrittenFields() != null) b.fieldDefinitions(ch.rewrittenFields());
            if (ch.newDescription() != null) b.description(ch.newDescription());
        });
    }

    private static ObjectTypeExtensionDefinition transformObjectExtension(ObjectTypeExtensionDefinition node, Map<String, SchemaInput> bySource) {
        var ch = computeObjectChanges(node.getFieldDefinitions(), node, bySource);
        if (!ch.any()) return null;
        return node.transformExtension(b -> {
            if (ch.rewrittenFields() != null) b.fieldDefinitions(ch.rewrittenFields());
            if (ch.newDescription() != null) b.description(ch.newDescription());
        });
    }

    private static InterfaceTypeDefinition transformInterface(InterfaceTypeDefinition node, Map<String, SchemaInput> bySource) {
        var ch = computeObjectChanges(node.getFieldDefinitions(), node, bySource);
        if (!ch.any()) return null;
        return node.transform(b -> {
            if (ch.rewrittenFields() != null) b.definitions(ch.rewrittenFields());
            if (ch.newDescription() != null) b.description(ch.newDescription());
        });
    }

    private static InterfaceTypeExtensionDefinition transformInterfaceExtension(InterfaceTypeExtensionDefinition node, Map<String, SchemaInput> bySource) {
        var ch = computeObjectChanges(node.getFieldDefinitions(), node, bySource);
        if (!ch.any()) return null;
        return node.transformExtension(b -> {
            if (ch.rewrittenFields() != null) b.definitions(ch.rewrittenFields());
            if (ch.newDescription() != null) b.description(ch.newDescription());
        });
    }

    private record InputChanges(List<InputValueDefinition> next, Description newDescription) {
        boolean any() { return next != null || newDescription != null; }
    }

    private static InputChanges computeInputChanges(
        List<InputValueDefinition> inputs, graphql.language.AbstractNode<?> node, Map<String, SchemaInput> bySource
    ) {
        var rewritten = rewriteInputValues(inputs, bySource);
        return new InputChanges(rewritten, descriptionToApply(node, bySource));
    }

    private static InputObjectTypeDefinition transformInputObject(InputObjectTypeDefinition node, Map<String, SchemaInput> bySource) {
        var ch = computeInputChanges(node.getInputValueDefinitions(), node, bySource);
        if (!ch.any()) return null;
        return node.transform(b -> {
            if (ch.next() != null) b.inputValueDefinitions(ch.next());
            if (ch.newDescription() != null) b.description(ch.newDescription());
        });
    }

    private static InputObjectTypeExtensionDefinition transformInputObjectExtension(InputObjectTypeExtensionDefinition node, Map<String, SchemaInput> bySource) {
        var ch = computeInputChanges(node.getInputValueDefinitions(), node, bySource);
        if (!ch.any()) return null;
        return node.transformExtension(b -> {
            if (ch.next() != null) b.inputValueDefinitions(ch.next());
            if (ch.newDescription() != null) b.description(ch.newDescription());
        });
    }

    private record EnumChanges(List<EnumValueDefinition> next, Description newDescription) {
        boolean any() { return next != null || newDescription != null; }
    }

    private static EnumChanges computeEnumChanges(
        List<EnumValueDefinition> values, graphql.language.AbstractNode<?> node, Map<String, SchemaInput> bySource
    ) {
        return new EnumChanges(rewriteEnumValues(values, bySource), descriptionToApply(node, bySource));
    }

    private static EnumTypeDefinition transformEnum(EnumTypeDefinition node, Map<String, SchemaInput> bySource) {
        var ch = computeEnumChanges(node.getEnumValueDefinitions(), node, bySource);
        if (!ch.any()) return null;
        return node.transform(b -> {
            if (ch.next() != null) b.enumValueDefinitions(ch.next());
            if (ch.newDescription() != null) b.description(ch.newDescription());
        });
    }

    private static EnumTypeExtensionDefinition transformEnumExtension(EnumTypeExtensionDefinition node, Map<String, SchemaInput> bySource) {
        var ch = computeEnumChanges(node.getEnumValueDefinitions(), node, bySource);
        if (!ch.any()) return null;
        return node.transformExtension(b -> {
            if (ch.next() != null) b.enumValueDefinitions(ch.next());
            if (ch.newDescription() != null) b.description(ch.newDescription());
        });
    }

    private static UnionTypeDefinition transformUnion(UnionTypeDefinition node, Map<String, SchemaInput> bySource) {
        var newDescription = descriptionToApply(node, bySource);
        if (newDescription == null) return null;
        return node.transform(b -> b.description(newDescription));
    }

    private static UnionTypeExtensionDefinition transformUnionExtension(UnionTypeExtensionDefinition node, Map<String, SchemaInput> bySource) {
        var newDescription = descriptionToApply(node, bySource);
        if (newDescription == null) return null;
        return node.transformExtension(b -> b.description(newDescription));
    }

    private static List<InputValueDefinition> rewriteInputValues(List<InputValueDefinition> inputs, Map<String, SchemaInput> bySource) {
        var next = new ArrayList<InputValueDefinition>(inputs.size());
        boolean changed = false;
        for (var input : inputs) {
            var noted = noteInputValue(input, bySource);
            if (noted != null) {
                next.add(noted);
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
            var newValueDesc = descriptionToApply(value, bySource);
            if (newValueDesc != null) {
                next.add(value.transform(b -> b.description(newValueDesc)));
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
            var newDesc = descriptionToApply(withArgs, bySource);
            if (newDesc != null) {
                next.add(withArgs.transform(b -> b.description(newDesc)));
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
            var noted = noteInputValue(arg, bySource);
            if (noted != null) {
                next.add(noted);
                changed = true;
            } else {
                next.add(arg);
            }
        }
        return changed ? next : null;
    }

    private static InputValueDefinition noteInputValue(InputValueDefinition node, Map<String, SchemaInput> bySource) {
        var newDesc = descriptionToApply(node, bySource);
        if (newDesc == null) return null;
        return node.transform(b -> b.description(newDesc));
    }

    private static Description descriptionToApply(
        graphql.language.AbstractNode<?> node,
        Map<String, SchemaInput> bySource
    ) {
        var input = lookup(node.getSourceLocation(), bySource);
        if (input == null || input.descriptionNote().isEmpty()) return null;
        var note = input.descriptionNote().get().strip();
        var existing = existingDescriptionContent(node);
        var content = existing == null || existing.isEmpty() ? note : existing.strip() + "\n\n" + note;
        return new Description(content, null, content.contains("\n"));
    }

    private static String existingDescriptionContent(graphql.language.AbstractNode<?> node) {
        Description existing = switch (node) {
            case ObjectTypeDefinition o -> o.getDescription();
            case InterfaceTypeDefinition i -> i.getDescription();
            case InputObjectTypeDefinition i -> i.getDescription();
            case EnumTypeDefinition e -> e.getDescription();
            case UnionTypeDefinition u -> u.getDescription();
            case FieldDefinition f -> f.getDescription();
            case InputValueDefinition v -> v.getDescription();
            case EnumValueDefinition v -> v.getDescription();
            default -> null;
        };
        return existing == null ? null : existing.getContent();
    }

    private static SchemaInput lookup(SourceLocation loc, Map<String, SchemaInput> bySource) {
        if (loc == null || loc.getSourceName() == null) return null;
        return bySource.get(loc.getSourceName());
    }
}
