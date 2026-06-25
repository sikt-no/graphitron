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
 *       jumps to the generated table class, column field, or FK constant. The
 *       position comes from the LSP-owned {@link SourceWalker.Index} at request
 *       time, joined by the table / {@code Keys} class FQN the catalog carries,
 *       so it rides the {@code .java} source cadence (R352).</li>
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
 * in the catalog. Both halves resolve positions from the LSP-owned
 * {@link SourceWalker.Index} at request time (not from the catalog) and route
 * the join outcome through one exhaustive switch on the typed
 * {@link DefinitionTarget}: a {@code Located} jumps, a {@code SourceAbsent}
 * (known reference, source not on a walked root) is a non-jump decided by the
 * type, not a sentinel. A same-arity overload collision is no longer a non-jump:
 * it falls back to the never-dropped name-level view and lands on a declaration
 * adjacent to the overload set.
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
                tableDefinition(location, catalog, sourceIndex, file.source());
            case Behavior.CatalogColumnBinding ignored ->
                fieldDefinition(directive, location, catalog, sourceIndex, snapshot, file.source());
            case Behavior.CatalogFkBinding ignored ->
                referenceKeyDefinition(catalog, sourceIndex,
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
     * index. First {@link DefinitionTarget.Located} wins (so the correct overload, or
     * a later resolvable one, jumps). When every arity key is absent or was dropped as
     * a same-arity overload collision, the resolution falls back to the never-dropped
     * name-level view ({@link SourceWalker.Index#methodByName}): landing on any
     * declaration of the name gets the developer adjacent to the overload set, which
     * beats declining. Only when the class carries no declaration of the name at all
     * is the outcome {@link DefinitionTarget.SourceAbsent}. Caller guards that the
     * class is known and carries at least one method of this name. Public for
     * LSP-tier arm tests.
     */
    public static DefinitionTarget methodTarget(
        String fqn, String methodName, CompletionData catalog, SourceWalker.Index sourceIndex
    ) {
        var ref = catalog.externalReferences().stream()
            .filter(r -> r.className().equals(fqn))
            .findFirst();
        if (ref.isEmpty()) return new DefinitionTarget.SourceAbsent();
        for (var method : ref.get().methods()) {
            if (!method.name().equals(methodName)) continue;
            var key = new SourceWalker.MethodKey(fqn, methodName, method.parameters().size());
            var decl = sourceIndex.methods().get(key);
            if (decl != null) return new DefinitionTarget.Located(decl.location());
        }
        return sourceIndex.methodByName(fqn, methodName)
            .<DefinitionTarget>map(d -> new DefinitionTarget.Located(d.location()))
            .orElseGet(DefinitionTarget.SourceAbsent::new);
    }

    /**
     * Goto projection of {@link SourceWalker.Index#resolveMethod}: the editor jump
     * for the method a {@link no.sikt.graphitron.lsp.parsing.DeclTarget.SourceMethod}
     * names, sharing the index's arity-then-name resolution with the declaration-name
     * hover overlay so the two stay structurally in lockstep.
     */
    public static Optional<Location> methodLocation(
        SourceWalker.Index sourceIndex, String fqn, String methodName, int paramCount
    ) {
        return sourceIndex.resolveMethod(fqn, methodName, paramCount).flatMap(d -> asLocation(d.location()));
    }

    /**
     * The single mapping from the typed service-half outcome to an editor
     * jump. {@code SourceAbsent} is a non-silent no-jump (logged, since it is
     * where the recoverable "source exists but isn't on a watched root" case
     * lands).
     */
    static Optional<Location> resolve(DefinitionTarget target, String fqn) {
        return switch (target) {
            case DefinitionTarget.Located located -> asLocation(located.location());
            case DefinitionTarget.SourceAbsent ignored -> {
                LOGGER.debug("goto-definition: {} is a known reference but no source position is indexed; "
                    + "is its declaring module's source root on the dev session?", fqn);
                yield Optional.empty();
            }
        };
    }

    /**
     * Pure join for a jOOQ field reference (column or FK constant): the
     * {@code (declaringClassFqn, fieldName)} key resolves against the source
     * index. {@link DefinitionTarget.Located} when present, else
     * {@link DefinitionTarget.SourceAbsent} (a {@code null} FQN, i.e. an
     * unresolvable table / {@code Keys} class, lands here too). jOOQ fields do
     * not collide on a single overload key, so there is no {@code Ambiguous} arm.
     */
    static DefinitionTarget fieldTarget(
        String declaringClassFqn, String fieldName, SourceWalker.Index sourceIndex
    ) {
        if (declaringClassFqn == null) return new DefinitionTarget.SourceAbsent();
        var decl = sourceIndex.fields().get(new SourceWalker.FieldKey(declaringClassFqn, fieldName));
        return decl != null
            ? new DefinitionTarget.Located(decl.location())
            : new DefinitionTarget.SourceAbsent();
    }

    /**
     * Table goto-definition, shared by {@code @table(name:)} and
     * {@code @reference(path: [{table:}])}, both of which resolve to
     * {@link Behavior.CatalogTableBinding} on the cursor's coordinate. The
     * generated table class is a class in the source index, so this reuses the
     * {@link #classTarget} join on the table's {@code classFqn}.
     */
    private static Optional<Location> tableDefinition(
        LspVocabulary.CursorLocation location, CompletionData catalog,
        SourceWalker.Index sourceIndex, byte[] source
    ) {
        String tableName = Nodes.unquote(Nodes.text(location.leafNode(), source));
        var tableOpt = catalog.getTable(tableName);
        if (tableOpt.isEmpty()) return Optional.empty();
        String classFqn = tableOpt.get().classFqn();
        return resolve(classTarget(classFqn, sourceIndex), classFqn);
    }

    /**
     * Column goto-definition for {@code @field(name:)}: resolves the enclosing
     * type's table, then the named column within it, then joins the
     * {@code (table classFqn, column name)} field key against the source index.
     */
    private static Optional<Location> fieldDefinition(
        Directives.Directive directive, LspVocabulary.CursorLocation location,
        CompletionData catalog, SourceWalker.Index sourceIndex,
        LspSchemaSnapshot snapshot, byte[] source
    ) {
        String columnName = Nodes.unquote(Nodes.text(location.leafNode(), source));
        var typeDecl = DeclarationKind.enclosing(directive.outer());
        if (typeDecl.isEmpty()) return Optional.empty();
        var tableName = TypeContext.tableNameOf(typeDecl.get(), source, snapshot);
        if (tableName.isEmpty()) return Optional.empty();
        var tableOpt = catalog.getTable(tableName.get());
        if (tableOpt.isEmpty()) return Optional.empty();
        var table = tableOpt.get();
        var columnOpt = table.columns().stream()
            .filter(c -> c.name().equalsIgnoreCase(columnName))
            .findFirst();
        // Unknown column is "not our target" (empty), distinct from a known
        // column whose source is not indexed (SourceAbsent, also a non-jump).
        if (columnOpt.isEmpty()) return Optional.empty();
        return resolve(fieldTarget(table.classFqn(), columnOpt.get().name(), sourceIndex), table.classFqn());
    }

    private static Optional<Location> referenceKeyDefinition(
        CompletionData catalog, SourceWalker.Index sourceIndex, String fkName
    ) {
        for (var table : catalog.tables()) {
            for (var ref : table.references()) {
                if (ref.keyName().equals(fkName)) {
                    return resolve(fieldTarget(ref.keysClassFqn(), fkName, sourceIndex), fkName);
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
