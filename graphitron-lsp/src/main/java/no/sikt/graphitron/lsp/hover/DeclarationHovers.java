package no.sikt.graphitron.lsp.hover;

import io.github.treesitter.jtreesitter.Node;
import io.github.treesitter.jtreesitter.Point;
import no.sikt.graphitron.lsp.facts.FieldInformation;
import no.sikt.graphitron.lsp.facts.TypeInformation;
import no.sikt.graphitron.lsp.parsing.Positions;
import no.sikt.graphitron.lsp.parsing.SdlDeclaration;
import no.sikt.graphitron.lsp.state.FileSnapshot;
import no.sikt.graphitron.model.read.StoreHandle;
import org.eclipse.lsp4j.Hover;
import org.eclipse.lsp4j.MarkupContent;
import org.eclipse.lsp4j.MarkupKind;
import org.eclipse.lsp4j.Range;

import java.util.Optional;

/**
 * Hover dispatch on SDL declaration coordinates. Parallel to {@link Hovers}'s
 * directive-argument-keyed dispatch: where {@link Hovers} keys on the cursor sitting inside a
 * directive, this dispatch keys on the cursor sitting on a field-declaration or type-declaration
 * <em>name token</em> (outside any directive).
 *
 * <p>The exhaustive switch over {@link DeclarationHover} permits enforces that any new SDL
 * declaration coordinate added to the sealed family fails to compile until its hover content lands
 * here in the same commit.
 *
 * <p>A declaration's hover is the information the store states about it, and no classification.
 * A field's is {@link FieldInformation}: its signature, what its fetcher runs or reads, the column or
 * type it reads, where its query starts, the target's node identity, the code it calls, what
 * graphitron adds at it, its written path and its defects. A type's is {@link TypeInformation}: its signature, the result it holds, its node
 * identity, its handlers, what graphitron adds at it and its defects. Every line is a key lookup in
 * a stored relation and none reads a view, which {@code EditorReadsNoViewTest} holds, and
 * either hover is one statement, which {@code DeclarationHoverStatementCountTest} holds.
 */
public final class DeclarationHovers {

    private DeclarationHovers() {}

    /**
     * Computes the declaration-name hover for {@code pos}. Returns {@link Optional#empty()} when the
     * cursor is not on a recognised SDL declaration name token, when there is no store, or when the
     * store does not know the declaration.
     */
    public static Optional<Hover> compute(
        FileSnapshot file, Optional<StoreHandle> store, Point pos
    ) {
        if (file == null || file.tree() == null) return Optional.empty();
        var declOpt = SdlDeclaration.findContaining(file.tree().getRootNode(), pos, file.source());
        if (declOpt.isEmpty()) return Optional.empty();
        if (store.isEmpty()) return Optional.empty();
        var handle = store.get();
        var declaration = toDeclarationHover(declOpt.get());
        var markdown = switch (declaration) {
            case DeclarationHover.FieldDeclarationHover f -> fieldInformation(handle, f);
            case DeclarationHover.TypeDeclarationHover t -> typeInformation(handle, t);
        };
        return markdown.map(text -> hover(file, declaration.nameNode(), text));
    }

    /** The field block, one statement over stored relations. Empty where the store does not know the field. */
    private static Optional<String> fieldInformation(
        StoreHandle store, DeclarationHover.FieldDeclarationHover f
    ) {
        var lines = FieldInformation.of(store, f.parentTypeName(), f.fieldName());
        var info = lines.read(store.dsl().select(lines.fields()).fetchOne());
        if (info.isEmpty()) return Optional.empty();
        return Optional.of(FieldInformationMarkdown.render(f.coordinate(), info));
    }

    /** The type block, on the field block's terms. Empty where the store does not know the type. */
    private static Optional<String> typeInformation(
        StoreHandle store, DeclarationHover.TypeDeclarationHover t
    ) {
        var lines = TypeInformation.of(store, t.typeName());
        var info = lines.read(store.dsl().select(lines.fields()).fetchOne());
        if (info.isEmpty()) return Optional.empty();
        return Optional.of(TypeInformationMarkdown.render(t.typeName(), info));
    }

    /**
     * Classifies the declaration name {@code pos} sits on, mapping the shared
     * {@link SdlDeclaration} trigger onto this feature's hover-content family.
     * The leaf-walk-and-classify itself lives in {@link SdlDeclaration} so the
     * hover and goto-definition triggers cannot drift apart; this method only
     * adapts the result.
     */
    public static Optional<DeclarationHover> findContaining(Node root, Point pos, byte[] source) {
        return SdlDeclaration.findContaining(root, pos, source).map(DeclarationHovers::toDeclarationHover);
    }

    private static DeclarationHover toDeclarationHover(SdlDeclaration declaration) {
        return switch (declaration) {
            case SdlDeclaration.TypeName t ->
                new DeclarationHover.TypeDeclarationHover(t.nameNode(), t.typeName());
            case SdlDeclaration.FieldName f ->
                new DeclarationHover.FieldDeclarationHover(f.nameNode(), f.parentTypeName(), f.fieldName());
        };
    }

    private static Hover hover(FileSnapshot file, Node anchor, String markdown) {
        var content = new MarkupContent(MarkupKind.MARKDOWN, markdown);
        var start = Positions.toLspPosition(file.source(), anchor.getStartByte());
        var end = Positions.toLspPosition(file.source(), anchor.getEndByte());
        return new Hover(content, new Range(start, end));
    }
}
