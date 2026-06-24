package no.sikt.graphitron.lsp.definition;

import io.github.treesitter.jtreesitter.Node;
import io.github.treesitter.jtreesitter.Point;
import no.sikt.graphitron.lsp.parsing.Directives;
import no.sikt.graphitron.lsp.parsing.Nodes;
import no.sikt.graphitron.lsp.parsing.SdlDeclaration;
import no.sikt.graphitron.lsp.parsing.TypeContext;
import no.sikt.graphitron.lsp.state.WorkspaceFile;
import no.sikt.graphitron.rewrite.catalog.CompletionData;
import no.sikt.graphitron.rewrite.catalog.LspSchemaSnapshot;
import no.sikt.graphitron.rewrite.catalog.SourceWalker;
import no.sikt.graphitron.rewrite.catalog.TypeBackingShape;
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
 * <p>Dispatch is an <b>exhaustive switch over {@link TypeBackingShape} with no
 * {@code default}</b> (mirroring {@link Definitions}'s switch over
 * {@code Behavior}): a future backing permit forces a goto-def decision at
 * compile time rather than silently resolving to nothing. Every arm resolves
 * through the sealed {@link DefinitionTarget} and {@link Definitions#resolve},
 * the single empty-resolution contract R349 settled on: {@code Located} jumps,
 * {@code SourceAbsent} / {@code Ambiguous} stay put. Members resolve by source
 * index key, joined from the catalog's structural keys (table / column / class
 * FQN) and the backing projection's member key:
 *
 * <ul>
 *   <li><b>Type name</b> -> the backing class (jOOQ table class for
 *       table-bound types; the consumer class for reflection-bound
 *       record / POJO / standalone-jOOQ types).</li>
 *   <li><b>Field name</b> -> the backing member: a jOOQ column
 *       ({@link Definitions#fieldTarget}), a record component (also a field in
 *       the parse tree, so {@code fieldTarget} on the component name), or a POJO
 *       bean accessor ({@link Definitions#methodTarget} on the slot's
 *       {@code accessorMethodName}). A standalone jOOQ record carries no column
 *       and no member key, so a field cursor degrades to its backing class.</li>
 * </ul>
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
        return switch (declOpt.get()) {
            case SdlDeclaration.TypeName t -> typeNameTarget(t.typeName(), built, catalog, sourceIndex);
            case SdlDeclaration.FieldName f -> fieldNameTarget(f, built, catalog, sourceIndex, file.source());
        };
    }

    /** The declaration name is a type name: jump to that type's backing class. */
    private static Optional<Location> typeNameTarget(
        String typeName, LspSchemaSnapshot.Built built, CompletionData catalog, SourceWalker.Index sourceIndex
    ) {
        var shapeOpt = built.typeBacking(typeName);
        if (shapeOpt.isEmpty()) return Optional.empty();
        return switch (shapeOpt.get()) {
            case TypeBackingShape.TableBacking t -> tableClassTarget(t.tableName(), catalog, sourceIndex);
            case TypeBackingShape.JooqRecordBacking.WithTable j -> tableClassTarget(j.tableName(), catalog, sourceIndex);
            case TypeBackingShape.JooqRecordBacking.Standalone s -> classTarget(s.fqClassName(), sourceIndex);
            case TypeBackingShape.RecordBacking r -> classTarget(r.fqClassName(), sourceIndex);
            case TypeBackingShape.PojoBacking p -> classTarget(p.fqClassName(), sourceIndex);
            case TypeBackingShape.NoBacking.Root ignored -> Optional.empty();
            case TypeBackingShape.NoBacking.UnbackedResult ignored -> Optional.empty();
            case TypeBackingShape.NoBacking.UnclassifiedInterface ignored -> Optional.empty();
        };
    }

    /** The declaration name is a field name: jump to the member it binds to. */
    private static Optional<Location> fieldNameTarget(
        SdlDeclaration.FieldName field, LspSchemaSnapshot.Built built,
        CompletionData catalog, SourceWalker.Index sourceIndex, byte[] source
    ) {
        var shapeOpt = built.typeBacking(field.parentTypeName());
        if (shapeOpt.isEmpty()) return Optional.empty();
        // The bound member is named by the field's @field(name:) override when it
        // carries one, else by the SDL field name itself. The field-definition
        // node is the declaration name's parent.
        Node fieldDef = field.nameNode().getParent().orElse(null);
        String memberName = fieldDef == null
            ? field.fieldName()
            : effectiveMemberName(fieldDef, field.fieldName(), source);
        return switch (shapeOpt.get()) {
            case TypeBackingShape.TableBacking t -> columnTarget(t.tableName(), memberName, catalog, sourceIndex);
            case TypeBackingShape.JooqRecordBacking.WithTable j -> columnTarget(j.tableName(), memberName, catalog, sourceIndex);
            case TypeBackingShape.PojoBacking p -> accessorTarget(p.accessors(), p.fqClassName(), memberName, catalog, sourceIndex);
            case TypeBackingShape.RecordBacking r -> componentTarget(r.components(), r.fqClassName(), memberName, sourceIndex);
            // A standalone jOOQ record has no table (no column join) and no
            // member-key projection, so a field cursor degrades to the backing
            // class, the same target as its type name.
            case TypeBackingShape.JooqRecordBacking.Standalone s -> classTarget(s.fqClassName(), sourceIndex);
            case TypeBackingShape.NoBacking.Root ignored -> Optional.empty();
            case TypeBackingShape.NoBacking.UnbackedResult ignored -> Optional.empty();
            case TypeBackingShape.NoBacking.UnclassifiedInterface ignored -> Optional.empty();
        };
    }

    /** Type name on a table-bound type: the generated table class. */
    private static Optional<Location> tableClassTarget(
        String tableName, CompletionData catalog, SourceWalker.Index sourceIndex
    ) {
        var tableOpt = catalog.getTable(tableName);
        if (tableOpt.isEmpty()) return Optional.empty();
        return classTarget(tableOpt.get().classFqn(), sourceIndex);
    }

    /** Field name on a table-bound type: the named column on the table class. */
    private static Optional<Location> columnTarget(
        String tableName, String columnName, CompletionData catalog, SourceWalker.Index sourceIndex
    ) {
        var tableOpt = catalog.getTable(tableName);
        if (tableOpt.isEmpty()) return Optional.empty();
        var table = tableOpt.get();
        var columnOpt = table.columns().stream()
            .filter(c -> c.name().equalsIgnoreCase(columnName))
            .findFirst();
        // Unknown column is "not our target" (empty), distinct from a known
        // column whose source is not indexed (SourceAbsent, also a non-jump).
        if (columnOpt.isEmpty()) return Optional.empty();
        return Definitions.resolve(
            Definitions.fieldTarget(table.classFqn(), columnOpt.get().name(), sourceIndex), table.classFqn());
    }

    /**
     * Field name on a POJO: the bean accessor method. The slot carries the real
     * arity-0 method name ({@code getFirstName}); the source index keys methods
     * by that name, not by the bean property name the author writes.
     */
    private static Optional<Location> accessorTarget(
        java.util.List<TypeBackingShape.MemberSlot> accessors, String fqClassName,
        String memberName, CompletionData catalog, SourceWalker.Index sourceIndex
    ) {
        return accessors.stream()
            .filter(s -> s.name().equals(memberName))
            .findFirst()
            .map(s -> Definitions.resolve(
                Definitions.methodTarget(fqClassName, s.accessorMethodName(), catalog, sourceIndex), fqClassName))
            .orElse(Optional.empty());
    }

    /**
     * Field name on a Java record: the component, which the parse-only source
     * walk indexes as a field (the implicit accessor is synthesised later, so it
     * is not in the parse tree). The component name is its own field key.
     */
    private static Optional<Location> componentTarget(
        java.util.List<TypeBackingShape.MemberSlot> components, String fqClassName,
        String memberName, SourceWalker.Index sourceIndex
    ) {
        return components.stream()
            .filter(s -> s.name().equals(memberName))
            .findFirst()
            .map(s -> Definitions.resolve(
                Definitions.fieldTarget(fqClassName, s.name(), sourceIndex), fqClassName))
            .orElse(Optional.empty());
    }

    private static Optional<Location> classTarget(String fqClassName, SourceWalker.Index sourceIndex) {
        return Definitions.resolve(Definitions.classTarget(fqClassName, sourceIndex), fqClassName);
    }

    /**
     * The member name the field binds to: its {@code @field(name:)} override when
     * present and non-empty, else the SDL field name. Reads the override off the
     * field-definition node so an authored binding resolves to its real member.
     */
    private static String effectiveMemberName(Node fieldDef, String fallback, byte[] source) {
        for (var directive : Directives.findAll(fieldDef)) {
            if (!"field".equals(Nodes.text(directive.nameNode(), source))) continue;
            String override = TypeContext.stringArg(directive.outer(), "name", source);
            if (override != null && !override.isEmpty()) return override;
        }
        return fallback;
    }
}
