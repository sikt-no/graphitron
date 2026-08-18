package no.sikt.graphitron.lsp.definition;

import io.github.treesitter.jtreesitter.Point;
import no.sikt.graphitron.lsp.facts.DeclarationFacts;
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
 * <p>{@link DeclTarget#of} performs the one backing-switch from SDL
 * coordinate to a named jOOQ / Java declaration; this provider only projects
 * the result. The declaration-name hover arm switches over the same
 * {@link DeclTarget}, so a new backing permit breaks both switches at compile
 * time. The catalog / class / column / field arms route through the sealed
 * {@link DefinitionTarget} and {@link Definitions#resolve}, the single
 * empty-resolution contract ({@code Located} jumps, {@code SourceAbsent} stays
 * put).
 *
 * <p>One request is one statement: {@link DeclarationFacts} answers what the coordinate resolves
 * against and what the java-source family holds about every declaration it could resolve to, and the
 * jump is a lookup over those rows. Hover's overlay reads the same rows for the same declaration's doc
 * comment, so the parity between the two surfaces is a property of the family rather than of two
 * readers agreeing, with one asymmetry left standing: a declaration the parse positioned but wrote no
 * doc comment for jumps without overlaying anything.
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
        var declOpt = SdlDeclaration.findContaining(file.tree().getRootNode(), pos, file.source());
        if (declOpt.isEmpty()) return Optional.empty();
        // No snapshot gate: the resolution and the position behind it are one statement over the
        // store's own relations, so a session that has captured but never generated jumps like any
        // other. What a completed build still buys is the one arm no relation carries, a @routine
        // field's generated call surface.
        var coord = DeclTarget.coordinateOf(declOpt.get(), file.source());
        var projected = DeclTarget.projectedMethod(coord, snapshot);
        var rows = DeclarationFacts.of(store, coord, projected);
        return locate(DeclTarget.of(coord, rows, projected), rows);
    }

    /**
     * Projects the shared {@link DeclTarget} to the editor jump for its declaration, out of the rows
     * the same statement brought back. Public so {@code DeclarationHoverOverlayParityTest} can assert,
     * per variant, that this jump is present exactly when the declaration-name hover overlay is (the
     * parity property), without a tree-sitter round-trip.
     *
     * <p>Every arm reads the java-source family and nothing else, which is why the rows can answer for
     * all of them: what separates a table's jump from a column's is which of that family's three
     * relations positions the declaration, and the resolution has already said which declaration it is.
     */
    public static Optional<Location> locate(DeclTarget target, DeclarationFacts.Rows rows) {
        return switch (target) {
            case DeclTarget.CatalogTable t -> classJump(rows, t.classFqn());
            case DeclTarget.CatalogColumn c -> fieldJump(rows, c.classFqn(), c.columnName());
            case DeclTarget.SourceClass s -> classJump(rows, s.fqClassName());
            case DeclTarget.SourceMethod m -> SourceDeclarations.byArityThenName(
                rows.methodLocationByArity(m.fqClassName(), m.methodName()), m.paramCount());
            case DeclTarget.SourceField f -> fieldJump(rows, f.fqClassName(), f.memberName());
            case DeclTarget.None ignored -> Optional.empty();
        };
    }

    private static Optional<Location> classJump(DeclarationFacts.Rows rows, String classFqn) {
        return Definitions.resolve(Definitions.located(
            rows.classDeclaration(classFqn).flatMap(DeclarationFacts.ClassRow::location)), classFqn);
    }

    private static Optional<Location> fieldJump(
        DeclarationFacts.Rows rows, String classFqn, String fieldName
    ) {
        return Definitions.resolve(Definitions.located(rows.fieldDeclaration(classFqn, fieldName)
            .flatMap(DeclarationFacts.FieldRow::location)), classFqn);
    }
}
