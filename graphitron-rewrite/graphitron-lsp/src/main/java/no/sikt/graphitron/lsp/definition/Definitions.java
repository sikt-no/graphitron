package no.sikt.graphitron.lsp.definition;

import no.sikt.graphitron.lsp.parsing.Behavior;
import no.sikt.graphitron.lsp.parsing.DeclarationKind;
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
import io.github.treesitter.jtreesitter.Node;
import io.github.treesitter.jtreesitter.Point;

import java.util.Optional;

import static no.sikt.graphitron.lsp.parsing.GraphqlNodeKind.NAME;
import static no.sikt.graphitron.lsp.parsing.GraphqlNodeKind.VALUE;

/**
 * Resolves cursor positions on known directive arguments to source
 * locations in the consumer's Java tree, so the editor's
 * "go-to-definition" jumps to the declaration. Two families are served:
 *
 * <ul>
 *   <li><b>jOOQ half</b> ({@code @table}, {@code @field}, {@code @reference}):
 *       jumps to the generated table class, column field, or FK constant.
 *       Positions are refined to the per-line declaration when the
 *       {@code SourceWalker} has parsed the generated sources; they fall back
 *       to the file head ({@code 0:0}) otherwise.</li>
 *   <li><b>service half</b> (the {@link Behavior.ClassNameBinding} /
 *       {@link Behavior.MethodNameBinding} directives: {@code @service},
 *       {@code @externalField}, {@code @enum}, {@code @condition},
 *       {@code @sourceRow}, {@code @tableMethod}): jumps to the consumer's
 *       Java class or method declaration, reusing the same
 *       {@link LspVocabulary#behaviorAt} / {@link LspVocabulary#siblingStringAt}
 *       dispatch the completion / hover paths use.</li>
 * </ul>
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
        String name = Nodes.text(directive.nameNode(), file.source());
        return switch (name) {
            case "table" -> tableDefinition(directive, file, catalog, pos);
            case "field" -> fieldDefinition(directive, file, catalog, snapshot, pos);
            case "reference" -> referenceDefinition(directive, file, catalog, pos);
            default -> bindingDefinition(vocabulary, directive, file, catalog, pos);
        };
    }

    /**
     * Service-half goto-definition: resolves the cursor coordinate through the
     * vocabulary overlay and, for a class-name or method-name binding, jumps to
     * the consumer's Java declaration. Other coordinates (and unknown
     * directives) return empty.
     */
    private static Optional<Location> bindingDefinition(
        LspVocabulary vocabulary, Directives.Directive directive,
        WorkspaceFile file, CompletionData catalog, Point pos
    ) {
        var locationOpt = vocabulary.locateAt(directive, pos, file.source());
        if (locationOpt.isEmpty()) return Optional.empty();
        var location = locationOpt.get();
        var behaviorOpt = vocabulary.behaviorAt(location.coordinate());
        if (behaviorOpt.isEmpty()) return Optional.empty();
        return switch (behaviorOpt.get()) {
            case Behavior.ClassNameBinding ignored ->
                classDefinition(location, catalog, file.source());
            case Behavior.MethodNameBinding mnb ->
                methodDefinition(vocabulary, directive, location, catalog, pos,
                    mnb.classNameCoord(), file.source());
            default -> Optional.empty();
        };
    }

    private static Optional<Location> classDefinition(
        LspVocabulary.CursorLocation location, CompletionData catalog, byte[] source
    ) {
        // @record's className is deprecated/ignored and binds no class; mirror
        // the completion / hover carve-out (the coordinate is shared with @enum).
        if ("record".equals(location.directiveName())) return Optional.empty();
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

    private static Optional<Location> tableDefinition(
        Directives.Directive directive, WorkspaceFile file, CompletionData catalog, Point pos
    ) {
        Node argValue = stringArgValueAt(directive, "name", pos, file.source());
        if (argValue == null) return Optional.empty();
        String tableName = Nodes.unquote(Nodes.text(argValue, file.source()));
        return catalog.getTable(tableName)
            .map(t -> t.definition())
            .flatMap(Definitions::asLocation);
    }

    private static Optional<Location> fieldDefinition(
        Directives.Directive directive, WorkspaceFile file, CompletionData catalog,
        LspSchemaSnapshot snapshot, Point pos
    ) {
        Node argValue = stringArgValueAt(directive, "name", pos, file.source());
        if (argValue == null) return Optional.empty();
        String columnName = Nodes.unquote(Nodes.text(argValue, file.source()));

        var typeDecl = DeclarationKind.enclosing(directive.outer());
        if (typeDecl.isEmpty()) return Optional.empty();
        var tableName = TypeContext.tableNameOf(typeDecl.get(), file.source(), snapshot);
        if (tableName.isEmpty()) return Optional.empty();
        var tableOpt = catalog.getTable(tableName.get());
        if (tableOpt.isEmpty()) return Optional.empty();
        return tableOpt.get().columns().stream()
            .filter(c -> c.name().equalsIgnoreCase(columnName))
            .findFirst()
            .map(CompletionData.Column::definition)
            .flatMap(Definitions::asLocation);
    }

    private static Optional<Location> referenceDefinition(
        Directives.Directive directive, WorkspaceFile file, CompletionData catalog, Point pos
    ) {
        for (var arg : directive.arguments()) {
            if (!"path".equals(Nodes.text(arg.key(), file.source()))) continue;
            Node field = Nodes.innermostObjectFieldContaining(arg.value(), pos);
            if (field == null) continue;
            Node nameNode = Nodes.childOfKind(field, NAME);
            Node valueNode = Nodes.childOfKind(field, VALUE);
            if (nameNode == null || valueNode == null) continue;
            if (!Nodes.contains(valueNode, pos)) continue;
            String fieldName = Nodes.text(nameNode, file.source());
            String value = Nodes.unquote(Nodes.text(valueNode, file.source()));
            return switch (fieldName) {
                case "key" -> referenceKeyDefinition(catalog, value);
                case "table" -> catalog.getTable(value)
                    .map(CompletionData.Table::definition)
                    .flatMap(Definitions::asLocation);
                default -> Optional.empty();
            };
        }
        return Optional.empty();
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

    private static Node stringArgValueAt(
        Directives.Directive directive, String argName, Point pos, byte[] source
    ) {
        for (var arg : directive.arguments()) {
            if (!arg.contains(pos)) continue;
            if (!argName.equals(Nodes.text(arg.key(), source))) continue;
            if (!Nodes.contains(arg.value(), pos)) continue;
            return arg.value();
        }
        return null;
    }

}
