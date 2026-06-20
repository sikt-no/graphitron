package no.sikt.graphitron.lsp.definition;

import no.sikt.graphitron.lsp.parsing.Behavior;
import no.sikt.graphitron.lsp.parsing.DeclarationKind;
import no.sikt.graphitron.lsp.parsing.DirectivePolicy;
import no.sikt.graphitron.lsp.parsing.Directives;
import no.sikt.graphitron.lsp.parsing.LspVocabulary;
import no.sikt.graphitron.lsp.parsing.Nodes;
import no.sikt.graphitron.lsp.parsing.SchemaCoordinate;
import no.sikt.graphitron.lsp.parsing.TypeContext;
import no.sikt.graphitron.lsp.state.WorkspaceFile;
import no.sikt.graphitron.rewrite.catalog.CompletionData;
import no.sikt.graphitron.rewrite.catalog.LspSchemaSnapshot;
import org.eclipse.lsp4j.Location;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import io.github.treesitter.jtreesitter.Point;

import java.util.Optional;

/**
 * Resolves cursor positions on known directive arguments to source
 * locations in the consumer's Java tree, so the editor's
 * "go-to-definition" jumps to the declaration. A single coordinate-driven
 * dispatch (resolve the cursor coordinate via {@link LspVocabulary#locateAt},
 * then switch on its {@link LspVocabulary#behaviorAt}, the same shape the
 * completion / diagnostic / hover paths use) serves two families:
 *
 * <ul>
 *   <li><b>jOOQ half</b> ({@link Behavior.CatalogTableBinding} /
 *       {@link Behavior.CatalogColumnBinding} / {@link Behavior.CatalogFkBinding},
 *       reached from {@code @table}, {@code @field}, and {@code @reference(path:)}):
 *       jumps to the generated table class, column field, or FK constant.
 *       Positions are refined to the per-line declaration when the
 *       {@code SourceWalker} has parsed the generated sources; they fall back
 *       to the file head ({@code 0:0}) otherwise.</li>
 *   <li><b>service half</b> ({@link Behavior.ClassNameBinding} /
 *       {@link Behavior.MethodNameBinding}: {@code @service},
 *       {@code @externalField}, {@code @enum}, {@code @condition},
 *       {@code @sourceRow}, {@code @tableMethod}): jumps to the consumer's
 *       Java class or method declaration via {@link LspVocabulary#siblingStringAt}.</li>
 * </ul>
 *
 * <p>Because dispatch is on the cursor's resolved coordinate rather than the
 * directive name, a class binding nested inside a jOOQ directive (e.g. a
 * {@code condition.className} inside {@code @reference(path:)}) resolves through
 * the service half rather than being silently ignored.
 *
 * <p>Returns {@link Optional#empty()} when the cursor is not on a
 * known directive arg, when the arg value does not resolve in the
 * catalog, or when the catalog entry has no source location
 * ({@link CompletionData.SourceLocation#UNKNOWN}: the source is not on the
 * build, or the method join key is overload-ambiguous), in which case the
 * editor stays put rather than jumping to a bogus location.
 */
public final class Definitions {

    private Definitions() {}

    /**
     * Back-compatible overload that loads the bundled vocabulary; the
     * service-half binding arm uses the canonical overlay. Production callers
     * pass the workspace vocabulary through
     * {@link #compute(LspVocabulary, WorkspaceFile, CompletionData, LspSchemaSnapshot, Point)}.
     */
    public static Optional<Location> compute(
        WorkspaceFile file, CompletionData catalog, LspSchemaSnapshot snapshot, Point pos
    ) {
        return compute(LspVocabulary.load(), file, catalog, snapshot, pos);
    }

    public static Optional<Location> compute(
        LspVocabulary vocabulary, WorkspaceFile file, CompletionData catalog,
        LspSchemaSnapshot snapshot, Point pos
    ) {
        var directiveOpt = Directives.findContaining(file.tree().getRootNode(), pos);
        if (directiveOpt.isEmpty()) return Optional.empty();
        var directive = directiveOpt.get();
        var locationOpt = vocabulary.locateAt(directive, pos, file.source());
        if (locationOpt.isEmpty()) return Optional.empty();
        var location = locationOpt.get();
        var behaviorOpt = vocabulary.behaviorAt(location.coordinate());
        if (behaviorOpt.isEmpty()) return Optional.empty();
        // One coordinate-driven dispatch for both halves, matching Diagnostics
        // and Hovers. The switch is exhaustive over Behavior (no default) so a
        // new binding arm forces a goto-definition decision here rather than
        // silently resolving to nothing.
        return switch (behaviorOpt.get()) {
            case Behavior.ClassNameBinding ignored ->
                classDefinition(location, catalog, file.source());
            case Behavior.MethodNameBinding mnb ->
                methodDefinition(vocabulary, directive, location, catalog, pos,
                    mnb.classNameCoord(), file.source());
            case Behavior.CatalogTableBinding ignored ->
                tableDefinition(location, catalog, file.source());
            case Behavior.CatalogColumnBinding ignored ->
                fieldDefinition(directive, location, catalog, snapshot, file.source());
            case Behavior.CatalogFkBinding ignored ->
                referenceKeyDefinition(catalog,
                    Nodes.unquote(Nodes.text(location.leafNode(), file.source())));
            // No Java declaration target: @argMapping content, @scalarType FQNs
            // (handled by the class-name half when bound), and @nodeId typeNames
            // point at SDL types, not consumer Java.
            case Behavior.ArgMappingBinding ignored -> Optional.empty();
            case Behavior.ScalarTypeBinding ignored -> Optional.empty();
            case Behavior.NodeTypeBinding ignored -> Optional.empty();
        };
    }

    private static Optional<Location> classDefinition(
        LspVocabulary.CursorLocation location, CompletionData catalog, byte[] source
    ) {
        // @record's className is deprecated/ignored and binds no class; mirror
        // the completion / hover carve-out (the coordinate is shared with @enum,
        // so the carve-out keys on the directive name; see DirectivePolicy).
        if (!DirectivePolicy.bindsLiveClass(location.directiveName())) return Optional.empty();
        String fqn = Nodes.unquote(Nodes.text(location.leafNode(), source));
        if (fqn.isEmpty()) return Optional.empty();
        return catalog.externalReferences().stream()
            .filter(r -> r.className().equals(fqn))
            .findFirst()
            .map(CompletionData.ExternalReference::definition)
            .flatMap(Definitions::asLocation);
    }

    private static Optional<Location> methodDefinition(
        LspVocabulary vocabulary, Directives.Directive directive,
        LspVocabulary.CursorLocation location, CompletionData catalog,
        Point pos, SchemaCoordinate classNameCoord, byte[] source
    ) {
        String methodName = Nodes.unquote(Nodes.text(location.leafNode(), source));
        if (methodName.isEmpty()) return Optional.empty();
        var fqn = vocabulary.siblingStringAt(directive, pos, classNameCoord, source);
        if (fqn.isEmpty()) return Optional.empty();
        return catalog.externalReferences().stream()
            .filter(r -> r.className().equals(fqn.get()))
            .findFirst()
            .flatMap(ref -> ref.methods().stream()
                .filter(m -> m.name().equals(methodName))
                .map(CompletionData.Method::definition)
                // Skip overload entries the walk left UNKNOWN so a later
                // resolvable overload of the same name still jumps.
                .filter(loc -> loc != null && !loc.uri().isEmpty())
                .findFirst())
            .flatMap(Definitions::asLocation);
    }

    /**
     * Table goto-definition, shared by {@code @table(name:)} and
     * {@code @reference(path: [{table:}])}, both of which resolve to
     * {@link Behavior.CatalogTableBinding} on the cursor's coordinate.
     */
    private static Optional<Location> tableDefinition(
        LspVocabulary.CursorLocation location, CompletionData catalog, byte[] source
    ) {
        String tableName = Nodes.unquote(Nodes.text(location.leafNode(), source));
        return catalog.getTable(tableName)
            .map(CompletionData.Table::definition)
            .flatMap(Definitions::asLocation);
    }

    /**
     * Column goto-definition for {@code @field(name:)}: resolves the enclosing
     * type's table, then the named column within it.
     */
    private static Optional<Location> fieldDefinition(
        Directives.Directive directive, LspVocabulary.CursorLocation location,
        CompletionData catalog, LspSchemaSnapshot snapshot, byte[] source
    ) {
        String columnName = Nodes.unquote(Nodes.text(location.leafNode(), source));
        var typeDecl = DeclarationKind.enclosing(directive.outer());
        if (typeDecl.isEmpty()) return Optional.empty();
        var tableName = TypeContext.tableNameOf(typeDecl.get(), source, snapshot);
        if (tableName.isEmpty()) return Optional.empty();
        var tableOpt = catalog.getTable(tableName.get());
        if (tableOpt.isEmpty()) return Optional.empty();
        return tableOpt.get().columns().stream()
            .filter(c -> c.name().equalsIgnoreCase(columnName))
            .findFirst()
            .map(CompletionData.Column::definition)
            .flatMap(Definitions::asLocation);
    }

    private static Optional<Location> referenceKeyDefinition(CompletionData catalog, String fkName) {
        for (var table : catalog.tables()) {
            for (var ref : table.references()) {
                if (ref.keyName().equals(fkName)) {
                    return asLocation(ref.definition());
                }
            }
        }
        return Optional.empty();
    }

    private static Optional<Location> asLocation(CompletionData.SourceLocation source) {
        if (source == null || source.uri().isEmpty()) {
            return Optional.empty();
        }
        var pos = new Position(source.line(), source.column());
        return Optional.of(new Location(source.uri(), new Range(pos, pos)));
    }

}
