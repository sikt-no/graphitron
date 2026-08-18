package no.sikt.graphitron.lsp.definition;

import io.github.treesitter.jtreesitter.Point;
import no.sikt.graphitron.lsp.facts.SourceDeclarations;
import no.sikt.graphitron.lsp.parsing.DeclTarget;
import no.sikt.graphitron.lsp.parsing.SdlDeclaration;
import no.sikt.graphitron.lsp.state.FileSnapshot;
import no.sikt.graphitron.model.read.StoreHandle;
import no.sikt.graphitron.rewrite.catalog.LspSchemaSnapshot;
import org.eclipse.lsp4j.Location;

import java.util.Optional;

/**
 * Goto-definition for an SDL <em>declaration name</em>: the cursor sits on a
 * type-declaration name or a field / input-value-declaration name (not a
 * directive argument), and the editor jumps to the Java the model bound that
 * declaration to. For reflection-bound types the declaration name is the
 * <em>only</em> navigation handle; they carry no class-naming directive.
 *
 * <p>Parallel to {@link Definitions} (directive-argument bindings) and
 * {@link IntraSchemaDefinitions} (intra-schema type references); all three are
 * chained with {@code .or()} in the definition handler. The declaration-name
 * trigger is owned by {@link SdlDeclaration#findContaining}, the same primitive
 * {@code DeclarationHovers} keys on, so this provider and the hover arm cannot
 * drift on "is this leaf a declaration name?".
 *
 * <p>{@link DeclTarget#resolve} performs the one backing-switch from SDL
 * coordinate to a named jOOQ / Java declaration; this provider only projects
 * the result. The declaration-name hover arm switches over the same
 * {@link DeclTarget}, so a new backing permit breaks both switches at compile
 * time. The catalog / class / column / field arms route through the sealed
 * {@link DefinitionTarget} and {@link Definitions#resolve}, the single
 * empty-resolution contract ({@code Located} jumps, {@code SourceAbsent} stays
 * put).
 *
 * <p>Hover's overlay and this jump now read one substrate again: both ask the fact
 * store's java-source family about the declaration the shared {@link DeclTarget}
 * names, one for its doc comment and one for its position. The parity between them
 * is therefore back to being a property of the family rather than of two readers
 * agreeing, with one asymmetry left standing: a declaration the parse positioned but
 * wrote no doc comment for jumps without overlaying anything.
 */
public final class DeclarationDefinitions {

    private DeclarationDefinitions() {}

    public static Optional<Location> compute(
        FileSnapshot file, Optional<StoreHandle> store,
        LspSchemaSnapshot snapshot, Point pos
    ) {
        return store.flatMap(handle -> compute(file, handle, snapshot, pos));
    }

    public static Optional<Location> compute(
        FileSnapshot file, StoreHandle store,
        LspSchemaSnapshot snapshot, Point pos
    ) {
        if (file == null || file.tree() == null) return Optional.empty();
        // The snapshot gate is a cost gate now rather than a capability one: the resolution reads the
        // store for everything but a @routine field's generated call surface, so what it withholds
        // from a captured-but-not-generated session is a jump the store could mostly answer. Lifting
        // it is what the resolution being one statement buys, the resolution costing several today.
        if (!(snapshot instanceof LspSchemaSnapshot.Built)) return Optional.empty();
        var declOpt = SdlDeclaration.findContaining(file.tree().getRootNode(), pos, file.source());
        if (declOpt.isEmpty()) return Optional.empty();
        return locate(DeclTarget.resolve(declOpt.get(), snapshot, store, file.source()), store);
    }

    /**
     * Projects the shared {@link DeclTarget} to the editor jump for its declaration.
     * Public so {@code DeclarationHoverOverlayParityTest} can assert, per variant,
     * that this jump is present exactly when the declaration-name hover overlay is
     * (the parity property), without a tree-sitter round-trip.
     */
    public static Optional<Location> locate(DeclTarget target, StoreHandle store) {
        return switch (target) {
            case DeclTarget.CatalogTable t ->
                Definitions.resolve(Definitions.classTarget(t.classFqn(), store), t.classFqn());
            case DeclTarget.CatalogColumn c ->
                Definitions.resolve(
                    Definitions.fieldTarget(c.classFqn(), c.columnName(), store), c.classFqn());
            case DeclTarget.SourceClass s ->
                Definitions.resolve(Definitions.classTarget(s.fqClassName(), store), s.fqClassName());
            case DeclTarget.SourceMethod m ->
                SourceDeclarations.methodLocation(store, m.fqClassName(), m.methodName(), m.paramCount());
            case DeclTarget.SourceField f ->
                Definitions.resolve(Definitions.fieldTarget(f.fqClassName(), f.memberName(), store), f.fqClassName());
            case DeclTarget.None ignored -> Optional.empty();
        };
    }
}
