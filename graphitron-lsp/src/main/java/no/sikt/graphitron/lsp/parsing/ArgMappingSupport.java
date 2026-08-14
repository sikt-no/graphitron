package no.sikt.graphitron.lsp.parsing;

import io.github.treesitter.jtreesitter.Node;
import io.github.treesitter.jtreesitter.Point;

import java.util.Optional;

/**
 * Resolution shared by the {@code argMapping} completion and diagnostic
 * consumers: deriving the sibling {@code className} / {@code method}
 * coordinates that scope an {@code argMapping} slot, and reading the names they carry.
 *
 * <p>Resolution stops at the pair of names. What that pair refers to is a census question, so both
 * consumers put it to the store rather than to a resolved Java object; the two overloads below
 * differ only in what the cursor is, a point on the completion path and a node on the diagnostic
 * one.
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
        return target(
            siblingCoord(argMappingCoord, "className")
                .flatMap(c -> vocabulary.siblingStringAt(directive, anchor, c, source)),
            siblingCoord(argMappingCoord, "method")
                .flatMap(c -> vocabulary.siblingStringAt(directive, anchor, c, source)));
    }

    /** The same read anchored on the slot's own node (diagnostics path). */
    public static Optional<MethodTarget> siblingMethodTarget(
        LspVocabulary vocabulary, Directives.Directive directive, Node anchor,
        SchemaCoordinate argMappingCoord, byte[] source
    ) {
        return target(
            siblingCoord(argMappingCoord, "className")
                .flatMap(c -> vocabulary.siblingStringAt(directive, anchor, c, source)),
            siblingCoord(argMappingCoord, "method")
                .flatMap(c -> vocabulary.siblingStringAt(directive, anchor, c, source)));
    }

    private static Optional<MethodTarget> target(
        Optional<String> className, Optional<String> methodName
    ) {
        return className.flatMap(c -> methodName.map(m -> new MethodTarget(c, m)));
    }
}
