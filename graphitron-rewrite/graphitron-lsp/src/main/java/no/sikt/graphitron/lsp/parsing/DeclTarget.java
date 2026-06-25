package no.sikt.graphitron.lsp.parsing;

import io.github.treesitter.jtreesitter.Node;
import no.sikt.graphitron.rewrite.catalog.CompletionData;
import no.sikt.graphitron.rewrite.catalog.LspSchemaSnapshot;
import no.sikt.graphitron.rewrite.catalog.TypeBackingShape;

import java.util.List;

/**
 * The Java declaration an SDL <em>declaration-name</em> coordinate (a type-name
 * or field-name token, the trigger {@link SdlDeclaration} owns) binds to, named
 * independently of how a consumer projects it. Goto-definition projects each
 * variant to a source {@code Location}; the declaration-name hover arm projects
 * each to a Javadoc overlay (R371).
 *
 * <p>The point of sharing the resolution is that hover/goto parity becomes
 * <em>structural</em> rather than asserted: a single backing-switch
 * ({@link #resolve}) produces the target, and the two consumers each switch over
 * the <em>same</em> {@code DeclTarget} exhaustively, so they cannot point at
 * different declarations and a new {@link TypeBackingShape} permit breaks both
 * switches at compile time. The per-consumer difference is only the final read:
 * {@code Decl.location()} for goto vs. {@code Decl.javadoc()} (plus the
 * catalog-description precedence the table/column arms honour) for hover.
 *
 * <p>The variants name the resolved declaration, not its location or Javadoc:
 * <ul>
 *   <li>{@link CatalogTable} / {@link CatalogColumn} — a jOOQ table class or a
 *       named column on it, carried as the resolved {@link CompletionData.Table}
 *       / {@link CompletionData.Column} so the two consumers share one lookup.</li>
 *   <li>{@link SourceClass} — a reflection-bound backing class (record / POJO /
 *       standalone jOOQ record), <em>and</em> a standalone-jOOQ field cursor,
 *       which has no column or member key and degrades to its class.</li>
 *   <li>{@link SourceMethod} — a POJO bean accessor (the arity-0 method the
 *       source index keys by, not the bean property name).</li>
 *   <li>{@link SourceField} — a Java record component (indexed as a field by the
 *       parse-only source walk).</li>
 *   <li>{@link None} — no Java declaration: a {@code NoBacking.*} type, or a
 *       member name that resolves to no column / slot.</li>
 * </ul>
 */
public sealed interface DeclTarget {

    /** A jOOQ table class. Hover precedence: the SQL comment wins, else the class Javadoc. */
    record CatalogTable(CompletionData.Table table) implements DeclTarget {}

    /** A named column on a jOOQ table class. */
    record CatalogColumn(CompletionData.Table table, CompletionData.Column column) implements DeclTarget {}

    /** A reflection-bound backing class, or a standalone-jOOQ field degrading to its class. */
    record SourceClass(String fqClassName) implements DeclTarget {}

    /** A POJO bean accessor method, keyed by its arity-0 Java method name. */
    record SourceMethod(String fqClassName, String accessorMethodName) implements DeclTarget {}

    /** A Java record component, keyed as a field by the parse-only source walk. */
    record SourceField(String fqClassName, String memberName) implements DeclTarget {}

    /** No Java declaration to resolve. */
    record None() implements DeclTarget {}

    /**
     * Resolves the declaration {@code declaration} binds to against the backing
     * projection on {@code built} and the catalog. The only tree-sitter-bound step
     * is reading a field's {@code @field(name:)} override off its node; the backing
     * switch itself is the pure {@link #ofType} / {@link #ofField} core, so the
     * source-index read is left to the per-consumer projection.
     */
    static DeclTarget resolve(
        SdlDeclaration declaration, LspSchemaSnapshot.Built built, CompletionData catalog, byte[] source
    ) {
        return switch (declaration) {
            case SdlDeclaration.TypeName t -> ofType(t.typeName(), built, catalog);
            case SdlDeclaration.FieldName f -> {
                // The bound member is named by the field's @field(name:) override
                // when it carries one, else by the SDL field name itself. The
                // field-definition node is the declaration name's parent.
                Node fieldDef = f.nameNode().getParent().orElse(null);
                String memberName = fieldDef == null
                    ? f.fieldName()
                    : effectiveMemberName(fieldDef, f.fieldName(), source);
                yield ofField(f.parentTypeName(), memberName, built, catalog);
            }
        };
    }

    /** Pure resolver core for a type-name coordinate (no tree-sitter, no source index). */
    static DeclTarget ofType(String typeName, LspSchemaSnapshot.Built built, CompletionData catalog) {
        var shapeOpt = built.typeBacking(typeName);
        if (shapeOpt.isEmpty()) return new None();
        return switch (shapeOpt.get()) {
            case TypeBackingShape.TableBacking t -> tableTarget(t.tableName(), catalog);
            case TypeBackingShape.JooqRecordBacking.WithTable j -> tableTarget(j.tableName(), catalog);
            case TypeBackingShape.JooqRecordBacking.Standalone s -> new SourceClass(s.fqClassName());
            case TypeBackingShape.RecordBacking r -> new SourceClass(r.fqClassName());
            case TypeBackingShape.PojoBacking p -> new SourceClass(p.fqClassName());
            case TypeBackingShape.NoBacking.Root ignored -> new None();
            case TypeBackingShape.NoBacking.UnbackedResult ignored -> new None();
            case TypeBackingShape.NoBacking.UnclassifiedInterface ignored -> new None();
        };
    }

    /**
     * Pure resolver core for a field-name coordinate, given the already-resolved
     * member name ({@code @field(name:)} override or SDL field name). No
     * tree-sitter, no source index.
     */
    static DeclTarget ofField(
        String parentTypeName, String memberName, LspSchemaSnapshot.Built built, CompletionData catalog
    ) {
        var shapeOpt = built.typeBacking(parentTypeName);
        if (shapeOpt.isEmpty()) return new None();
        return switch (shapeOpt.get()) {
            case TypeBackingShape.TableBacking t -> columnTarget(t.tableName(), memberName, catalog);
            case TypeBackingShape.JooqRecordBacking.WithTable j -> columnTarget(j.tableName(), memberName, catalog);
            case TypeBackingShape.PojoBacking p -> accessorTarget(p.accessors(), p.fqClassName(), memberName);
            case TypeBackingShape.RecordBacking r -> componentTarget(r.components(), r.fqClassName(), memberName);
            // A standalone jOOQ record has no table (no column join) and no
            // member-key projection, so a field cursor degrades to the backing
            // class, the same target as its type name.
            case TypeBackingShape.JooqRecordBacking.Standalone s -> new SourceClass(s.fqClassName());
            case TypeBackingShape.NoBacking.Root ignored -> new None();
            case TypeBackingShape.NoBacking.UnbackedResult ignored -> new None();
            case TypeBackingShape.NoBacking.UnclassifiedInterface ignored -> new None();
        };
    }

    private static DeclTarget tableTarget(String tableName, CompletionData catalog) {
        return catalog.getTable(tableName)
            .<DeclTarget>map(CatalogTable::new)
            .orElseGet(None::new);
    }

    private static DeclTarget columnTarget(String tableName, String memberName, CompletionData catalog) {
        var tableOpt = catalog.getTable(tableName);
        if (tableOpt.isEmpty()) return new None();
        var table = tableOpt.get();
        // Unknown column is "no target", the same non-jump goto's columnTarget
        // returns; the case-insensitive match yields the canonical column name.
        return table.columns().stream()
            .filter(c -> c.name().equalsIgnoreCase(memberName))
            .findFirst()
            .<DeclTarget>map(c -> new CatalogColumn(table, c))
            .orElseGet(None::new);
    }

    private static DeclTarget accessorTarget(
        List<TypeBackingShape.MemberSlot> accessors, String fqClassName, String memberName
    ) {
        return accessors.stream()
            .filter(s -> s.name().equals(memberName))
            .findFirst()
            .<DeclTarget>map(s -> new SourceMethod(fqClassName, s.accessorMethodName()))
            .orElseGet(None::new);
    }

    private static DeclTarget componentTarget(
        List<TypeBackingShape.MemberSlot> components, String fqClassName, String memberName
    ) {
        return components.stream()
            .filter(s -> s.name().equals(memberName))
            .findFirst()
            .<DeclTarget>map(s -> new SourceField(fqClassName, s.name()))
            .orElseGet(None::new);
    }

    /**
     * The member name the field binds to: its {@code @field(name:)} override when
     * present and non-empty, else the SDL field name.
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
