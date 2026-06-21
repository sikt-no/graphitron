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
import no.sikt.graphitron.rewrite.catalog.SourceWalker;
import org.eclipse.lsp4j.Location;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import io.github.treesitter.jtreesitter.Point;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
 * <p>Returns {@link Optional#empty()} when the cursor is not on a known
 * directive arg and when the arg value does not resolve to a known reference
 * in the catalog. For the service half it also returns empty on the two
 * no-jump arms of {@link DefinitionTarget} (source absent / overload
 * ambiguous), but it decides those through an exhaustive switch on the typed
 * outcome rather than a sentinel; positions for the service half come from the
 * LSP-owned {@link SourceWalker.Index} at request time, not from the catalog.
 * The jOOQ half ({@code @table} / {@code @field} / {@code @reference}) still
 * reads its position from the catalog's {@code SourceLocation} (build cadence;
 * see roadmap R352 for lifting it onto the source index too).
 */
public final class Definitions {

    private static final Logger LOGGER = LoggerFactory.getLogger(Definitions.class);

    private Definitions() {}

    /**
     * Back-compatible overload that loads the bundled vocabulary; the
     * service-half binding arm uses the canonical overlay. Production callers
     * pass the workspace vocabulary through
     * {@link #compute(LspVocabulary, WorkspaceFile, CompletionData, SourceWalker.Index, LspSchemaSnapshot, Point)}.
     */
    public static Optional<Location> compute(
        WorkspaceFile file, CompletionData catalog, SourceWalker.Index sourceIndex,
        LspSchemaSnapshot snapshot, Point pos
    ) {
        return compute(LspVocabulary.load(), file, catalog, sourceIndex, snapshot, pos);
    }

    public static Optional<Location> compute(
        LspVocabulary vocabulary, WorkspaceFile file, CompletionData catalog,
        SourceWalker.Index sourceIndex, LspSchemaSnapshot snapshot, Point pos
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
                classDefinition(location, catalog, sourceIndex, file.source());
            case Behavior.MethodNameBinding mnb ->
                methodDefinition(vocabulary, directive, location, catalog, sourceIndex, pos,
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
        LspVocabulary.CursorLocation location, CompletionData catalog,
        SourceWalker.Index sourceIndex, byte[] source
    ) {
        // @record's className is deprecated/ignored and binds no class; mirror
        // the completion / hover carve-out (the coordinate is shared with @enum,
        // so the carve-out keys on the directive name; see DirectivePolicy).
        if (!DirectivePolicy.bindsLiveClass(location.directiveName())) return Optional.empty();
        String fqn = Nodes.unquote(Nodes.text(location.leafNode(), source));
        if (fqn.isEmpty()) return Optional.empty();
        // Unknown class name (not a scanned reference) is "not our target" —
        // empty, distinct from the SourceAbsent arm of a known reference.
        boolean known = catalog.externalReferences().stream()
            .anyMatch(r -> r.className().equals(fqn));
        if (!known) return Optional.empty();
        return resolve(classTarget(fqn, sourceIndex), fqn);
    }

    private static Optional<Location> methodDefinition(
        LspVocabulary vocabulary, Directives.Directive directive,
        LspVocabulary.CursorLocation location, CompletionData catalog,
        SourceWalker.Index sourceIndex, Point pos, SchemaCoordinate classNameCoord, byte[] source
    ) {
        String methodName = Nodes.unquote(Nodes.text(location.leafNode(), source));
        if (methodName.isEmpty()) return Optional.empty();
        var fqnOpt = vocabulary.siblingStringAt(directive, pos, classNameCoord, source);
        if (fqnOpt.isEmpty()) return Optional.empty();
        String fqn = fqnOpt.get();
        var ref = catalog.externalReferences().stream()
            .filter(r -> r.className().equals(fqn))
            .findFirst();
        // Unknown class, or a known class with no method of this name, is "not
        // our target" — empty, distinct from the typed no-jump arms below.
        if (ref.isEmpty() || ref.get().methods().stream().noneMatch(m -> m.name().equals(methodName))) {
            return Optional.empty();
        }
        return resolve(methodTarget(fqn, methodName, catalog, sourceIndex), fqn);
    }

    /**
     * Pure FQN → position join for a class reference: {@link DefinitionTarget.Located}
     * when the source index has the class, {@link DefinitionTarget.SourceAbsent}
     * otherwise (class FQNs do not collide on a single overload key, so there is
     * no {@code Ambiguous} arm for classes). Caller guards that {@code fqn} is a
     * known reference. Public so the LSP tier can assert each arm directly.
     */
    public static DefinitionTarget classTarget(String fqn, SourceWalker.Index sourceIndex) {
        var decl = sourceIndex.classes().get(fqn);
        return decl != null
            ? new DefinitionTarget.Located(decl.location())
            : new DefinitionTarget.SourceAbsent();
    }

    /**
     * Pure join for a method reference: for each catalog method of {@code methodName}
     * on {@code fqn}, the {@code (fqn, name, arity)} key resolves against the source
     * index. First {@link DefinitionTarget.Located} wins (so a later resolvable
     * overload still jumps); else {@link DefinitionTarget.Ambiguous} when any
     * candidate key was dropped as an overload collision; else
     * {@link DefinitionTarget.SourceAbsent}. Caller guards that the class is known
     * and carries at least one method of this name. Public for LSP-tier arm tests.
     */
    public static DefinitionTarget methodTarget(
        String fqn, String methodName, CompletionData catalog, SourceWalker.Index sourceIndex
    ) {
        var ref = catalog.externalReferences().stream()
            .filter(r -> r.className().equals(fqn))
            .findFirst();
        if (ref.isEmpty()) return new DefinitionTarget.SourceAbsent();
        boolean sawAmbiguous = false;
        for (var method : ref.get().methods()) {
            if (!method.name().equals(methodName)) continue;
            var key = new SourceWalker.MethodKey(fqn, methodName, method.parameters().size());
            var decl = sourceIndex.methods().get(key);
            if (decl != null) return new DefinitionTarget.Located(decl.location());
            if (sourceIndex.ambiguousMethods().contains(key)) sawAmbiguous = true;
        }
        return sawAmbiguous ? new DefinitionTarget.Ambiguous() : new DefinitionTarget.SourceAbsent();
    }

    /**
     * The single mapping from the typed service-half outcome to an editor
     * jump. {@code SourceAbsent} is a non-silent no-jump (logged, since it is
     * where the recoverable "source exists but isn't on a watched root" case
     * lands); {@code Ambiguous} is a deliberate silent no-jump.
     */
    private static Optional<Location> resolve(DefinitionTarget target, String fqn) {
        return switch (target) {
            case DefinitionTarget.Located located -> asLocation(located.location());
            case DefinitionTarget.SourceAbsent ignored -> {
                LOGGER.debug("goto-definition: {} is a known reference but no source position is indexed; "
                    + "is its declaring module's source root on the dev session?", fqn);
                yield Optional.empty();
            }
            case DefinitionTarget.Ambiguous ignored -> Optional.empty();
        };
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
