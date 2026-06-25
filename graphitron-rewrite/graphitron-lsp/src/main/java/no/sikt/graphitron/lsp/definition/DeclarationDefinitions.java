package no.sikt.graphitron.lsp.definition;

import io.github.treesitter.jtreesitter.Point;
import no.sikt.graphitron.lsp.parsing.DeclTarget;
import no.sikt.graphitron.lsp.parsing.SdlDeclaration;
import no.sikt.graphitron.lsp.state.WorkspaceFile;
import no.sikt.graphitron.rewrite.catalog.CompletionData;
import no.sikt.graphitron.rewrite.catalog.LspSchemaSnapshot;
import no.sikt.graphitron.rewrite.catalog.SourceWalker;
import org.eclipse.lsp4j.Location;

import java.util.Optional;

/**
 * Goto-definition for an SDL <em>declaration name</em>: the cursor sits on a
 * type-declaration name or a field / input-value-declaration name (not a
 * directive argument), and the editor jumps to the Java the model bound that
 * declaration to. The declaration name is where the cursor naturally rests, and
 * for reflection-bound types it is the <em>only</em> navigation handle (they
 * carry no class-naming directive at all).
 *
 * <p>Parallel to {@link Definitions} (directive-argument bindings) and
 * {@link IntraSchemaDefinitions} (intra-schema type references): all three are
 * chained with {@code .or()} in the definition handler. The shared
 * declaration-name trigger is owned by {@link SdlDeclaration#findContaining}
 * (the same primitive {@code DeclarationHovers} keys on), so this provider and
 * the hover arm cannot drift on "is this leaf a declaration name?".
 *
 * <p>The <em>binding</em> resolution is also shared: {@link DeclTarget#resolve}
 * performs the one backing-switch from SDL coordinate to a named jOOQ / Java
 * declaration, and this provider only projects each {@link DeclTarget} variant
 * to a {@code Location}. The declaration-name hover arm projects the same
 * {@code DeclTarget} to a Javadoc overlay, so hover/goto parity is structural:
 * both switch over the same target, and a new backing permit breaks both
 * switches at compile time. Every arm routes through the sealed
 * {@link DefinitionTarget} and {@link Definitions#resolve}, the single
 * empty-resolution contract R349 settled on: {@code Located} jumps,
 * {@code SourceAbsent} / {@code Ambiguous} stay put.
 */
public final class DeclarationDefinitions {

    private DeclarationDefinitions() {}

    public static Optional<Location> compute(
        WorkspaceFile file, CompletionData catalog, SourceWalker.Index sourceIndex,
        LspSchemaSnapshot snapshot, Point pos
    ) {
        if (file == null || file.tree() == null) return Optional.empty();
        if (!(snapshot instanceof LspSchemaSnapshot.Built built)) return Optional.empty();
        var declOpt = SdlDeclaration.findContaining(file.tree().getRootNode(), pos, file.source());
        if (declOpt.isEmpty()) return Optional.empty();
        return locate(DeclTarget.resolve(declOpt.get(), built, catalog, file.source()), catalog, sourceIndex);
    }

    /**
     * Projects the shared {@link DeclTarget} to the editor jump for its declaration.
     * Public so {@code DeclarationHoverOverlayParityTest} can assert, per variant,
     * that this jump is present exactly when the declaration-name hover overlay is
     * (the R371 parity property), without a tree-sitter round-trip.
     */
    public static Optional<Location> locate(
        DeclTarget target, CompletionData catalog, SourceWalker.Index sourceIndex
    ) {
        return switch (target) {
            case DeclTarget.CatalogTable t ->
                Definitions.resolve(Definitions.classTarget(t.table().classFqn(), sourceIndex), t.table().classFqn());
            case DeclTarget.CatalogColumn c ->
                Definitions.resolve(
                    Definitions.fieldTarget(c.table().classFqn(), c.column().name(), sourceIndex), c.table().classFqn());
            case DeclTarget.SourceClass s ->
                Definitions.resolve(Definitions.classTarget(s.fqClassName(), sourceIndex), s.fqClassName());
            case DeclTarget.SourceMethod m ->
                Definitions.resolve(
                    Definitions.methodTarget(m.fqClassName(), m.accessorMethodName(), catalog, sourceIndex), m.fqClassName());
            case DeclTarget.SourceField f ->
                Definitions.resolve(Definitions.fieldTarget(f.fqClassName(), f.memberName(), sourceIndex), f.fqClassName());
            case DeclTarget.None ignored -> Optional.empty();
        };
    }
}
