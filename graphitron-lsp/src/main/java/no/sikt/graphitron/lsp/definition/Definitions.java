package no.sikt.graphitron.lsp.definition;

import no.sikt.graphitron.lsp.facts.SourceDeclarations;
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
import no.sikt.graphitron.rewrite.catalog.CompletionData;
import no.sikt.graphitron.rewrite.catalog.LspSchemaSnapshot;
import org.eclipse.lsp4j.Location;
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
 *       position comes from the fact store's java-source family at request time,
 *       joined by the table / {@code Keys} class FQN the catalog carries, so it
 *       rides the {@code .java} source cadence.</li>
 *   <li><b>service half</b> ({@link Behavior.ClassNameBinding} /
 *       {@link Behavior.MethodNameBinding}: {@code @service},
 *       {@code @externalField}, {@code @enum}, {@code @condition},
 *       {@code @sourceRow}): jumps to the consumer's
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
 * in the catalog. Both halves resolve positions from the fact store's
 * java-source family at request time (not from the catalog) and route the join
 * outcome through one exhaustive switch on the typed {@link DefinitionTarget}:
 * a {@code Located} jumps, a {@code SourceAbsent} (known reference, no
 * positioned declaration) is a non-jump decided by the type, not a sentinel.
 *
 * <p>Two populations still meet here, on two cadences. Whether a name is a
 * reference at all is the classpath census's answer, which the catalog
 * projection carries and this provider guards on; where its declaration sits is
 * the {@code .java} parse's, which the store holds and
 * {@link SourceDeclarations} reads. The guard is what keeps an unknown name an
 * empty answer rather than a {@code SourceAbsent} one.
 */
public final class Definitions {

    private static final Logger LOGGER = LoggerFactory.getLogger(Definitions.class);

    private Definitions() {}

    /**
     * Back-compatible overload that loads the bundled vocabulary; the
     * service-half binding arm uses the canonical overlay. Production callers
     * pass the workspace vocabulary through
     * {@link #compute(LspVocabulary, FileSnapshot, CompletionData, StoreHandle, LspSchemaSnapshot, Point)}.
     */
    public static Optional<Location> compute(
        FileSnapshot file, CompletionData catalog, StoreHandle store,
        LspSchemaSnapshot snapshot, Point pos
    ) {
        return compute(LspVocabulary.load(), file, catalog, store, snapshot, pos);
    }

    /**
     * The production entry point. Every arm here ends in a declaration position, and
     * the java-source family is where those live, so a session with no store access
     * declines once here rather than per arm.
     */
    public static Optional<Location> compute(
        LspVocabulary vocabulary, FileSnapshot file, CompletionData catalog,
        Optional<StoreHandle> store, LspSchemaSnapshot snapshot, Point pos
    ) {
        return store.flatMap(handle -> compute(vocabulary, file, catalog, handle, snapshot, pos));
    }

    public static Optional<Location> compute(
        LspVocabulary vocabulary, FileSnapshot file, CompletionData catalog,
        StoreHandle store, LspSchemaSnapshot snapshot, Point pos
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
                classDefinition(location, catalog, store, file.source());
            case Behavior.MethodNameBinding mnb ->
                methodDefinition(vocabulary, directive, location, catalog, store, pos,
                    mnb.classNameCoord(), file.source());
            case Behavior.CatalogTableBinding ignored ->
                tableDefinition(location, catalog, store, file.source());
            case Behavior.CatalogColumnBinding ignored ->
                fieldDefinition(directive, location, catalog, store, snapshot, file.source());
            case Behavior.CatalogFkBinding ignored ->
                referenceKeyDefinition(catalog, store,
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
        StoreHandle store, byte[] source
    ) {
        // @record's className is deprecated/ignored and binds no class; mirror
        // the completion / hover carve-out (the coordinate is shared with @enum,
        // so the carve-out keys on the directive name; see DirectivePolicy).
        if (!DirectivePolicy.bindsLiveClass(location.directiveName())) return Optional.empty();
        String fqn = Nodes.unquote(Nodes.text(location.leafNode(), source));
        if (fqn.isEmpty()) return Optional.empty();
        // Unknown class name (not a scanned reference) is "not our target":
        // empty, distinct from the SourceAbsent arm of a known reference.
        boolean known = catalog.externalReferences().stream()
            .anyMatch(r -> r.className().equals(fqn));
        if (!known) return Optional.empty();
        return resolve(classTarget(fqn, store), fqn);
    }

    private static Optional<Location> methodDefinition(
        LspVocabulary vocabulary, Directives.Directive directive,
        LspVocabulary.CursorLocation location, CompletionData catalog,
        StoreHandle store, Point pos, SchemaCoordinate classNameCoord, byte[] source
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
        // our target": empty, distinct from the typed no-jump arms below.
        if (ref.isEmpty() || ref.get().methods().stream().noneMatch(m -> m.name().equals(methodName))) {
            return Optional.empty();
        }
        return resolve(methodTarget(fqn, methodName, catalog, store), fqn);
    }

    /**
     * Pure FQN → position join for a class reference: {@link DefinitionTarget.Located}
     * when the java-source family positions the class, {@link DefinitionTarget.SourceAbsent}
     * otherwise. Caller guards that {@code fqn} is a known reference. Public so the
     * LSP tier can assert each arm directly.
     */
    public static DefinitionTarget classTarget(String fqn, StoreHandle store) {
        return located(SourceDeclarations.classLocation(store, fqn));
    }

    /**
     * Pure join for a method reference: the arities the census carries for
     * {@code methodName} on {@code fqn} are tried against the arities the source
     * declares, and the first that both hold wins. When no census arity is declared,
     * resolution falls back to the first declaration of the name, landing the
     * developer on the overload set rather than declining; only a class with no
     * positioned declaration of the name at all yields
     * {@link DefinitionTarget.SourceAbsent}.
     *
     * <p>The census arities are tried before the fallback rather than one at a time
     * through it, because a fallback consulted per arity would answer the first
     * census overload with some other overload's position while a later census arity
     * matched exactly. Caller guards that the class is known and carries at least one
     * method of this name. Public for LSP-tier arm tests.
     */
    public static DefinitionTarget methodTarget(
        String fqn, String methodName, CompletionData catalog, StoreHandle store
    ) {
        var declared = SourceDeclarations.methodLocationByArity(store, fqn, methodName);
        if (declared.isEmpty()) return new DefinitionTarget.SourceAbsent();
        var ref = catalog.externalReferences().stream()
            .filter(r -> r.className().equals(fqn))
            .findFirst();
        if (ref.isEmpty()) return new DefinitionTarget.SourceAbsent();
        for (var method : ref.get().methods()) {
            if (!method.name().equals(methodName)) continue;
            var declaration = declared.get(method.parameters().size());
            if (declaration != null) return new DefinitionTarget.Located(declaration);
        }
        return new DefinitionTarget.Located(declared.firstEntry().getValue());
    }

    /** The one mapping from "the family positions this declaration" to the typed outcome. */
    private static DefinitionTarget located(Optional<Location> location) {
        return location.<DefinitionTarget>map(DefinitionTarget.Located::new)
            .orElseGet(DefinitionTarget.SourceAbsent::new);
    }

    /**
     * The single mapping from the typed outcome to an editor jump.
     * {@code SourceAbsent} is a non-silent no-jump (logged, since it is where the
     * recoverable "source exists but isn't on a watched root" case lands).
     */
    static Optional<Location> resolve(DefinitionTarget target, String fqn) {
        return switch (target) {
            case DefinitionTarget.Located located -> Optional.of(located.location());
            case DefinitionTarget.SourceAbsent ignored -> {
                LOGGER.debug("goto-definition: {} is a known reference but the java-source family "
                    + "positions no declaration for it; is its declaring module's source root on "
                    + "the dev session?", fqn);
                yield Optional.empty();
            }
        };
    }

    /**
     * Pure join for a jOOQ field reference (column or FK constant): the
     * {@code (declaringClassFqn, fieldName)} pair resolves against the java-source
     * family. {@link DefinitionTarget.Located} when present, else
     * {@link DefinitionTarget.SourceAbsent} (a {@code null} FQN, i.e. an
     * unresolvable table / {@code Keys} class, lands here too).
     */
    static DefinitionTarget fieldTarget(
        String declaringClassFqn, String fieldName, StoreHandle store
    ) {
        return located(SourceDeclarations.fieldLocation(store, declaringClassFqn, fieldName));
    }

    /**
     * Table goto-definition, shared by {@code @table(name:)} and
     * {@code @reference(path: [{table:}])}. The generated table class is a class
     * declaration like any other, so this reuses the {@link #classTarget} join on
     * the table's {@code classFqn}.
     */
    private static Optional<Location> tableDefinition(
        LspVocabulary.CursorLocation location, CompletionData catalog,
        StoreHandle store, byte[] source
    ) {
        String tableName = Nodes.unquote(Nodes.text(location.leafNode(), source));
        var tableOpt = catalog.getTable(tableName);
        if (tableOpt.isEmpty()) return Optional.empty();
        String classFqn = tableOpt.get().classFqn();
        return resolve(classTarget(classFqn, store), classFqn);
    }

    /**
     * Column goto-definition for {@code @field(name:)}.
     */
    private static Optional<Location> fieldDefinition(
        Directives.Directive directive, LspVocabulary.CursorLocation location,
        CompletionData catalog, StoreHandle store,
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
        return resolve(fieldTarget(table.classFqn(), columnOpt.get().name(), store), table.classFqn());
    }

    private static Optional<Location> referenceKeyDefinition(
        CompletionData catalog, StoreHandle store, String fkName
    ) {
        for (var table : catalog.tables()) {
            for (var ref : table.references()) {
                if (ref.keyName().equals(fkName)) {
                    return resolve(fieldTarget(ref.keysClassFqn(), fkName, store), fkName);
                }
            }
        }
        return Optional.empty();
    }

}
