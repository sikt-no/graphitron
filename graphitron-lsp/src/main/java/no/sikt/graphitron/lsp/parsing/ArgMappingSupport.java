package no.sikt.graphitron.lsp.parsing;

import no.sikt.graphitron.rewrite.catalog.CompletionData;
import io.github.treesitter.jtreesitter.Node;
import io.github.treesitter.jtreesitter.Point;

import java.util.Optional;

/**
 * Resolution shared by the {@code argMapping} completion and diagnostic
 * consumers: deriving the sibling {@code className} / {@code method}
 * coordinates that scope an {@code argMapping} slot, and resolving what they name.
 *
 * <p>Two resolutions, mid-migration. {@link #siblingMethodTarget} stops at the pair of names, for
 * the consumer that asks the store what they refer to; the {@link CompletionData} form below answers
 * with a resolved method off the projection, and retires with the last surface reading it.
 *
 * <p>An {@code argMapping} slot is always one field of a class/method group:
 * the nested {@code ExternalCodeReference.{className, method, argMapping}} (on
 * {@code @service} / {@code @condition} / {@code @externalField}) or the flat
 * {@code @sourceRow(className:, method:)} shape. The sibling
 * coordinates are the same shape with the field name swapped, so the same
 * {@link LspVocabulary#siblingStringAt} read the method / class providers use
 * resolves the values here.
 */
public final class ArgMappingSupport {

    private ArgMappingSupport() {}

    /** Sibling coordinate with the field name swapped, for the two slot shapes. */
    public static Optional<SchemaCoordinate> siblingCoord(SchemaCoordinate argMappingCoord, String field) {
        return switch (argMappingCoord) {
            case SchemaCoordinate.InputField f -> Optional.of(new SchemaCoordinate.InputField(f.type(), field));
            case SchemaCoordinate.DirectiveArg da -> Optional.of(new SchemaCoordinate.DirectiveArg(da.directive(), field));
            case SchemaCoordinate.Directive ignored -> Optional.empty();
            case SchemaCoordinate.InputType ignored -> Optional.empty();
        };
    }

    /**
     * The class and method the slot's siblings name, which is as far as the syntax goes: what that
     * pair refers to is a census question, and a consumer reading facts asks it of the store rather
     * than through a resolved Java object.
     */
    public record MethodTarget(String className, String methodName) {}

    /** Cursor-anchored sibling read (completion path). Empty unless both siblings carry a value. */
    public static Optional<MethodTarget> siblingMethodTarget(
        LspVocabulary vocabulary, Directives.Directive directive, Point anchor,
        SchemaCoordinate argMappingCoord, byte[] source
    ) {
        String className = siblingCoord(argMappingCoord, "className")
            .flatMap(c -> vocabulary.siblingStringAt(directive, anchor, c, source)).orElse(null);
        String methodName = siblingCoord(argMappingCoord, "method")
            .flatMap(c -> vocabulary.siblingStringAt(directive, anchor, c, source)).orElse(null);
        if (className == null || methodName == null) return Optional.empty();
        return Optional.of(new MethodTarget(className, methodName));
    }

    /** Node-anchored method resolution (diagnostics path). */
    public static Optional<CompletionData.Method> resolveMethod(
        LspVocabulary vocabulary, Directives.Directive directive, Node anchor,
        SchemaCoordinate argMappingCoord, CompletionData catalog, byte[] source
    ) {
        String className = siblingCoord(argMappingCoord, "className")
            .flatMap(c -> vocabulary.siblingStringAt(directive, anchor, c, source)).orElse(null);
        String methodName = siblingCoord(argMappingCoord, "method")
            .flatMap(c -> vocabulary.siblingStringAt(directive, anchor, c, source)).orElse(null);
        return lookup(catalog, className, methodName);
    }

    private static Optional<CompletionData.Method> lookup(
        CompletionData catalog, String className, String methodName
    ) {
        if (className == null || methodName == null) return Optional.empty();
        return catalog.externalReferences().stream()
            .filter(r -> r.className().equals(className))
            .findFirst()
            .flatMap(ref -> ref.methods().stream()
                .filter(m -> m.name().equals(methodName))
                .findFirst());
    }
}
