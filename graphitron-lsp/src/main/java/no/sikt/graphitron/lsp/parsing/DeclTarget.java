package no.sikt.graphitron.lsp.parsing;

import io.github.treesitter.jtreesitter.Node;
import no.sikt.graphitron.lsp.facts.CatalogColumns;
import no.sikt.graphitron.lsp.facts.CatalogTables;
import no.sikt.graphitron.lsp.facts.ClassMemberSlots;
import no.sikt.graphitron.lsp.facts.ClasspathMethods;
import no.sikt.graphitron.model.read.StoreHandle;
import no.sikt.graphitron.rewrite.catalog.FieldClassification;
import no.sikt.graphitron.rewrite.catalog.LspSchemaSnapshot;
import no.sikt.graphitron.rewrite.catalog.TypeBackingShape;

import java.util.Optional;

/**
 * The Java declaration an SDL <em>declaration-name</em> coordinate (a type-name
 * or field-name token, the trigger {@link SdlDeclaration} owns) binds to, named
 * independently of how a consumer projects it. Goto-definition projects each
 * variant to a source {@code Location}; the declaration-name hover arm projects
 * each to a Javadoc overlay.
 *
 * <p>Sharing the resolution is what keeps the two consumers pointing at one
 * declaration: a single backing-switch ({@link #resolve}) produces the target,
 * and each consumer switches over the <em>same</em> {@code DeclTarget}
 * exhaustively, so a new {@link TypeBackingShape} permit breaks both switches at
 * compile time. What differs is where each then reads: hover asks the fact
 * store's java-source family for the declaration's doc comment, goto still asks
 * the LSP-owned source index for its position, and the two agree while both are
 * refreshed off the same parse. The variants name the resolved declaration and
 * nothing else, so neither read can be short-circuited by a value the
 * resolution happened to have in hand.
 */
public sealed interface DeclTarget {

    /**
     * A jOOQ table class, named by the table it was generated for and the class the catalog census
     * recorded. Names only: what either consumer then reads about the table, a position or a
     * description, is a fact neither this type nor the resolution carries.
     */
    record CatalogTable(String tableName, String classFqn) implements DeclTarget {}

    /** A named column on a jOOQ table class, under the census's own spelling of the column. */
    record CatalogColumn(String tableName, String classFqn, String columnName) implements DeclTarget {}

    /** A reflection-bound backing class, or a standalone-jOOQ field degrading to its class. */
    record SourceClass(String fqClassName) implements DeclTarget {}

    /**
     * A Java method the field binds to (a POJO bean accessor, or a
     * {@code @service} / {@code @externalField} method),
     * keyed by class, method name, and the bound parameter count.
     */
    record SourceMethod(String fqClassName, String methodName, int paramCount) implements DeclTarget {}

    /** A Java record component, keyed as a field by the parse-only source walk. */
    record SourceField(String fqClassName, String memberName) implements DeclTarget {}

    /** No Java declaration to resolve. */
    record None() implements DeclTarget {}

    /**
     * Resolves the declaration {@code declaration} binds to against the backing
     * projection on {@code built} and the store's censuses. The only tree-sitter-bound step
     * is reading a field's {@code @field(name:)} override off its node; the backing
     * switch itself is the {@link #ofType} / {@link #ofField} core, so the
     * source-index read is left to the per-consumer projection. The store answers what every
     * backing offers: a table's generated class and columns, a class's member slots, a method's
     * arity. Where the declaration <em>is</em> is still the consumer's own read.
     */
    static DeclTarget resolve(
        SdlDeclaration declaration, LspSchemaSnapshot.Built built,
        StoreHandle store, byte[] source
    ) {
        return switch (declaration) {
            case SdlDeclaration.TypeName t -> ofType(t.typeName(), built, store);
            case SdlDeclaration.FieldName f -> {
                // The bound member is named by the field's @field(name:) override
                // when it carries one, else by the SDL field name itself. The
                // field-definition node is the declaration name's parent.
                Node fieldDef = f.nameNode().getParent().orElse(null);
                String memberName = fieldDef == null
                    ? f.fieldName()
                    : effectiveMemberName(fieldDef, f.fieldName(), source);
                yield ofField(f.parentTypeName(), memberName, built, store);
            }
        };
    }

    /** Pure resolver core for a type-name coordinate (no tree-sitter, no source index). */
    static DeclTarget ofType(String typeName, LspSchemaSnapshot.Built built, StoreHandle store) {
        var shapeOpt = built.typeBacking(typeName);
        if (shapeOpt.isEmpty()) return new None();
        return switch (shapeOpt.get()) {
            case TypeBackingShape.TableBacking t -> tableTarget(store, t.tableName());
            case TypeBackingShape.JooqRecordBacking.WithTable j -> tableTarget(store, j.tableName());
            case TypeBackingShape.JooqRecordBacking.Standalone s -> new SourceClass(s.fqClassName());
            case TypeBackingShape.RecordBacking r -> new SourceClass(r.fqClassName());
            case TypeBackingShape.PojoBacking p -> new SourceClass(p.fqClassName());
            case TypeBackingShape.NoBacking.Root ignored -> new None();
            case TypeBackingShape.NoBacking.UnbackedResult ignored -> new None();
            case TypeBackingShape.NoBacking.UnclassifiedInterface ignored -> new None();
        };
    }

    /**
     * Resolver core for a field-name coordinate, given the already-resolved
     * member name ({@code @field(name:)} override or SDL field name). No
     * tree-sitter, no source index; the store read is the member-slot relation, which names the
     * declaration a member binds to without saying where it is written.
     */
    static DeclTarget ofField(
        String parentTypeName, String memberName, LspSchemaSnapshot.Built built, StoreHandle store
    ) {
        // A method-backed field (@service / @externalField / @routine) is
        // bound to its Java method, not to a column on the parent's table, so the
        // classification takes precedence over the parent-type backing below.
        var methodBacked = methodBackedTarget(parentTypeName, memberName, built, store);
        if (methodBacked.isPresent()) return methodBacked.get();
        var shapeOpt = built.typeBacking(parentTypeName);
        if (shapeOpt.isEmpty()) return new None();
        return switch (shapeOpt.get()) {
            case TypeBackingShape.TableBacking t -> columnTarget(store, t.tableName(), memberName);
            case TypeBackingShape.JooqRecordBacking.WithTable j -> columnTarget(store, j.tableName(), memberName);
            case TypeBackingShape.PojoBacking p -> memberTarget(store, p.fqClassName(), memberName);
            case TypeBackingShape.RecordBacking r -> memberTarget(store, r.fqClassName(), memberName);
            // A standalone jOOQ record has no table (no column join) and no
            // member-key projection, so a field cursor degrades to the backing
            // class, the same target as its type name.
            case TypeBackingShape.JooqRecordBacking.Standalone s -> new SourceClass(s.fqClassName());
            case TypeBackingShape.NoBacking.Root ignored -> new None();
            case TypeBackingShape.NoBacking.UnbackedResult ignored -> new None();
            case TypeBackingShape.NoBacking.UnclassifiedInterface ignored -> new None();
        };
    }

    /**
     * The method a method-backed field binds to, when the field's classification
     * names one. {@code memberName} is the SDL field name here: the method-backed
     * variants carry no {@code @field(name:)} override (that override redirects a
     * column / accessor binding, a different classification), so the resolved
     * member name is the coordinate the classification map is keyed by. The bound
     * arity is read off the census's method of that name; when the name is
     * arity-overloaded the classification does not record which overload bound, so
     * the first candidate's arity is taken and the consumers' name-level
     * fallback still guarantees a jump if that exact arity key was dropped.
     */
    private static Optional<DeclTarget> methodBackedTarget(
        String parentTypeName, String memberName, LspSchemaSnapshot.Built built, StoreHandle store
    ) {
        var classOpt = built.fieldClassification(parentTypeName, memberName);
        if (classOpt.isEmpty()) return Optional.empty();
        // Every classification outside these method-backed arms binds no developer
        // method and falls through to the parent-type backing.
        return switch (classOpt.get()) {
            case FieldClassification.ServiceBacked s -> Optional.of(sourceMethod(store, s.methodClassName(), s.methodName()));
            case FieldClassification.Computed c -> Optional.of(sourceMethod(store, c.methodClassName(), c.methodName()));
            case FieldClassification.QueryService q -> Optional.of(sourceMethod(store, q.methodClassName(), q.methodName()));
            case FieldClassification.RoutineBacked q -> Optional.of(sourceMethod(store, q.methodClassName(), q.methodName()));
            case FieldClassification.MutationService m -> Optional.of(sourceMethod(store, m.methodClassName(), m.methodName()));
            default -> Optional.empty();
        };
    }

    private static DeclTarget sourceMethod(StoreHandle store, String className, String methodName) {
        return new SourceMethod(className, methodName, arityOf(store, className, methodName));
    }

    /**
     * Parameter count of the first overload of {@code methodName} the census holds on
     * {@code className}, or 0 when it holds none (the name-level fallback on the source index then
     * guarantees the jump). First in the census's own order, which is by descriptor, so an
     * arity-overloaded name resolves the same way on every read.
     */
    private static int arityOf(StoreHandle store, String className, String methodName) {
        return ClasspathMethods.named(store, className, methodName).stream()
            .findFirst()
            .map(ClasspathMethods.Method::arity)
            .orElse(0);
    }

    /**
     * The generated class for a table the parent is bound to. A name two schemas both declare takes
     * the first in schema order, as the other surfaces resolving a spelling to one declaration do:
     * which one was meant is a resolution question, and a single declaration target cannot hold both.
     */
    private static DeclTarget tableTarget(StoreHandle store, String tableName) {
        return switch (CatalogTables.named(store, tableName)) {
            case CatalogTables.Match.Tables(var tables) -> {
                var table = tables.getFirst();
                yield new CatalogTable(table.tableName(), table.classFqn());
            }
            case CatalogTables.Match.Unknown ignored -> new None();
            case CatalogTables.Match.NoCensus ignored -> new None();
        };
    }

    /**
     * The generated field for a column on that table, named the way the class declares it: the census
     * carries the SQL name and the jOOQ one, an author may have written either, and what a consumer
     * then reads about the declaration is keyed by the second.
     */
    private static DeclTarget columnTarget(StoreHandle store, String tableName, String memberName) {
        if (!(tableTarget(store, tableName) instanceof CatalogTable table)) return new None();
        return CatalogColumns.of(store, tableName).stream()
            .filter(column -> column.isNamed(memberName))
            .findFirst()
            .<DeclTarget>map(column ->
                new CatalogColumn(table.tableName(), table.classFqn(), column.jooqName()))
            .orElseGet(None::new);
    }

    /**
     * The declaration behind a member name on a class-backed type. Which of the two it is follows the
     * slot the store answered with rather than the permit that routed the arm here: a record
     * component is written as a field, a bean accessor as a method, and the relation says which kind
     * the name came from. A name the class offers no slot for resolves to no target, as before.
     */
    private static DeclTarget memberTarget(StoreHandle store, String fqClassName, String memberName) {
        return ClassMemberSlots.named(store, fqClassName, memberName)
            .<DeclTarget>map(slot -> switch (slot.origin()) {
                case RECORD_COMPONENT -> new SourceField(fqClassName, slot.name());
                case BEAN_ACCESSOR -> new SourceMethod(fqClassName, slot.accessorMethodName(), 0);
            })
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
