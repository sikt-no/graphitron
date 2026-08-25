package no.sikt.graphitron.lsp.references;

import io.github.treesitter.jtreesitter.Point;
import no.sikt.graphitron.lsp.facts.BindingUsages;
import no.sikt.graphitron.lsp.facts.BoundTables;
import no.sikt.graphitron.lsp.facts.CatalogColumns;
import no.sikt.graphitron.lsp.facts.CatalogKeys;
import no.sikt.graphitron.lsp.facts.CatalogTable;
import no.sikt.graphitron.lsp.facts.CatalogTables;
import no.sikt.graphitron.lsp.facts.FieldColumnTable;
import no.sikt.graphitron.lsp.facts.SdlTypeUsages;
import no.sikt.graphitron.lsp.parsing.Behavior;
import no.sikt.graphitron.lsp.parsing.DeclarationKind;
import no.sikt.graphitron.lsp.parsing.DirectivePolicy;
import no.sikt.graphitron.lsp.parsing.Directives;
import no.sikt.graphitron.lsp.parsing.LspVocabulary;
import no.sikt.graphitron.lsp.parsing.Nodes;
import no.sikt.graphitron.lsp.parsing.SchemaCoordinate;
import no.sikt.graphitron.lsp.parsing.TypeContext;
import no.sikt.graphitron.lsp.state.FileSnapshot;
import no.sikt.graphitron.model.read.StoreHandle;
import org.eclipse.lsp4j.Location;

import java.util.List;

/**
 * Find-references for a directive argument: the cursor sits on a name a directive binds, and the
 * answer is every SDL coordinate in the schema that binds the same thing.
 *
 * <p>The reverse of {@code Definitions}, arm for arm, and dispatched the same way: resolve the
 * cursor to a coordinate through {@link LspVocabulary#locateAt}, switch on its {@link Behavior}. The
 * switch is exhaustive with no default, so a new binding arm forces a find-references decision here
 * rather than resolving quietly to nothing, which is the property the definition side already has.
 *
 * <p>Where the two differ is what they do with the name once they have it. Definition resolves it to
 * a declaration and leaves SDL for the Java or jOOQ tree. This resolves it to a target and then asks
 * the decoded relations who else binds that target, so the answer never leaves the schema. There is
 * no {@code SourceAbsent} analogue: the reverse direction never asks whether a declaration was
 * positioned, so an empty list means nothing else binds the target, full stop.
 */
public final class BindingReferences {

    private BindingReferences() {}

    /**
     * The coordinates binding what the cursor names, or an empty list when the cursor is not inside
     * a directive argument this surface answers for.
     */
    public static List<Location> compute(
        LspVocabulary vocabulary, FileSnapshot file, StoreHandle store, Point pos,
        boolean includeDeclaration
    ) {
        if (file == null || file.tree() == null) return List.of();
        var directiveOpt = Directives.findContaining(file.tree().getRootNode(), pos);
        if (directiveOpt.isEmpty()) return List.of();
        var directive = directiveOpt.get();
        var locationOpt = vocabulary.locateAt(directive, pos, file.source());
        if (locationOpt.isEmpty()) return List.of();
        var location = locationOpt.get();
        var behaviorOpt = vocabulary.behaviorAt(location.coordinate());
        if (behaviorOpt.isEmpty()) return List.of();

        return switch (behaviorOpt.get()) {
            case Behavior.ClassNameBinding ignored -> classUses(location, store, file.source());
            case Behavior.MethodNameBinding mnb ->
                methodUses(vocabulary, directive, location, store, pos, mnb.classNameCoord(),
                    file.source());
            case Behavior.CatalogTableBinding ignored -> tableUses(location, store, file.source());
            case Behavior.CatalogColumnBinding ignored ->
                columnUses(directive, location, store, file.source());
            case Behavior.CatalogFkBinding ignored -> keyUses(location, store, file.source());
            // A @nodeId(typeName:) names an SDL type, so its uses are that type's. The population
            // is the type arm's rather than a second one of this arm's own: an author asking who
            // uses Film should get one answer whether they asked from the type or from the
            // directive that names it.
            case Behavior.NodeTypeBinding ignored -> SdlTypeUsages.of(store,
                Nodes.unquote(Nodes.text(location.leafNode(), file.source())), includeDeclaration);
            // Both address things this surface does not yet take as subjects: an @argMapping path
            // addresses input fields, and a @scalarType FQN is bound through the class arm when it
            // is bound at all. The dispatch matrix records these as gaps rather than declines.
            case Behavior.ArgMappingBinding ignored -> List.of();
            case Behavior.ScalarTypeBinding ignored -> List.of();
        };
    }

    /**
     * Every coordinate naming the same class. The {@code @record} carve-out is the definition
     * side's: that directive's class name is deprecated and binds nothing live, so there is no
     * binding to find other users of.
     */
    private static List<Location> classUses(
        LspVocabulary.CursorLocation location, StoreHandle store, byte[] source
    ) {
        if (!DirectivePolicy.bindsLiveClass(location.directiveName())) return List.of();
        return BindingUsages.ofClass(store, Nodes.unquote(Nodes.text(location.leafNode(), source)));
    }

    /**
     * Every coordinate naming the same method on the same class. The class comes from the sibling
     * argument, as it does for the jump: a method name alone names nothing.
     */
    private static List<Location> methodUses(
        LspVocabulary vocabulary, Directives.Directive directive,
        LspVocabulary.CursorLocation location, StoreHandle store, Point pos,
        SchemaCoordinate classNameCoord, byte[] source
    ) {
        String method = Nodes.unquote(Nodes.text(location.leafNode(), source));
        return vocabulary.siblingStringAt(directive, pos, classNameCoord, source)
            .map(fqn -> BindingUsages.ofMethod(store, fqn, method))
            .orElseGet(List::of);
    }

    /**
     * Every coordinate bound to the table the cursor's spelling resolves to. The spelling resolves
     * through the census reader the completion and jump arms use, so an ambiguous name contributes
     * every table it could mean rather than being decided here.
     */
    private static List<Location> tableUses(
        LspVocabulary.CursorLocation location, StoreHandle store, byte[] source
    ) {
        String spelling = Nodes.unquote(Nodes.text(location.leafNode(), source));
        return switch (CatalogTables.named(store, spelling)) {
            case CatalogTables.Match.Tables(var tables) -> BindingUsages.ofTable(store,
                tables.stream().map(CatalogTables.Table::key).toList());
            case CatalogTables.Match.Unknown ignored -> List.of();
            case CatalogTables.Match.NoCensus ignored -> List.of();
        };
    }

    /**
     * Every coordinate bound to the same column. Which table the cursor's column belongs to is the
     * enclosing field's own scope, read through {@link FieldColumnTable}: the same resolution the
     * jump makes from this coordinate, so a jump and a usage list cannot disagree about which
     * column is under the cursor.
     */
    private static List<Location> columnUses(
        Directives.Directive directive, LspVocabulary.CursorLocation location,
        StoreHandle store, byte[] source
    ) {
        String spelling = Nodes.unquote(Nodes.text(location.leafNode(), source));
        var typeDecl = DeclarationKind.enclosing(directive.outer());
        if (typeDecl.isEmpty()) return List.of();
        var typeName = TypeContext.declaredNameOf(typeDecl.get(), source);
        if (typeName.isEmpty()) return List.of();
        var fieldName = TypeContext.enclosingFieldOrInputValueDefinition(directive.outer())
            .flatMap(fd -> TypeContext.fieldNameOf(fd, source));
        if (fieldName.isEmpty()) return List.of();
        var scope = FieldColumnTable.of(store, typeName.get(), fieldName.get());
        if (scope.isPresent()) {
            return switch (scope.get()) {
                case FieldColumnTable.Scope.Resolved(var table) -> columnUses(store, table, spelling);
                // No column resolves at this coordinate at all, so the parent's binding must not
                // stand in for one; the jump makes the same carve-out from the same read.
                case FieldColumnTable.Scope.Silent ignored -> List.of();
            };
        }
        // The field's own scope says nothing, so the enclosing type's binding answers. This is the
        // arm the jump falls back to as well, and a field that names its column through @field
        // without navigating anywhere lands here.
        for (var table : BoundTables.of(store, typeName.get())) {
            var uses = columnUses(store, table, spelling);
            if (!uses.isEmpty()) return uses;
        }
        return List.of();
    }

    /** The column's own census row decides which column the spelling means, then the reverse read. */
    private static List<Location> columnUses(
        StoreHandle store, CatalogTable table, String spelling
    ) {
        return CatalogColumns.of(store, table).stream()
            .filter(column -> column.isNamed(spelling))
            .findFirst()
            .map(column -> BindingUsages.ofColumn(store, table, column.columnName()))
            .orElseGet(List::of);
    }

    /**
     * Every {@code @reference} path hop keyed on the same constraint, under any spelling the
     * generator's own resolver accepts.
     */
    private static List<Location> keyUses(
        LspVocabulary.CursorLocation location, StoreHandle store, byte[] source
    ) {
        String spelling = Nodes.unquote(Nodes.text(location.leafNode(), source));
        return BindingUsages.ofKey(store, CatalogKeys.named(store, spelling));
    }
}
