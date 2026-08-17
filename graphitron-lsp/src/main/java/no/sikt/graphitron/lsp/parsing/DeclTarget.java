package no.sikt.graphitron.lsp.parsing;

import io.github.treesitter.jtreesitter.Node;
import no.sikt.graphitron.lsp.facts.CatalogColumns;
import no.sikt.graphitron.lsp.facts.CatalogTables;
import no.sikt.graphitron.lsp.facts.ClassMemberSlots;
import no.sikt.graphitron.lsp.facts.ClasspathMethods;
import no.sikt.graphitron.lsp.facts.TypeMemberScope;
import no.sikt.graphitron.model.read.StoreHandle;
import no.sikt.graphitron.rewrite.catalog.FieldClassification;
import no.sikt.graphitron.rewrite.catalog.LspSchemaSnapshot;

import java.util.Optional;

/**
 * The Java declaration an SDL <em>declaration-name</em> coordinate (a type-name
 * or field-name token, the trigger {@link SdlDeclaration} owns) binds to, named
 * independently of how a consumer projects it. Goto-definition projects each
 * variant to a source {@code Location}; the declaration-name hover arm projects
 * each to a Javadoc overlay.
 *
 * <p>Sharing the resolution is what keeps the two consumers pointing at one
 * declaration: a single scope-switch ({@link #resolve}) produces the target,
 * and each consumer switches over the <em>same</em> {@code DeclTarget}
 * exhaustively, so a new {@link TypeMemberScope.Scope} arm breaks both switches
 * at compile time. What differs is where each then reads: hover asks the fact
 * store's java-source family for the declaration's doc comment, goto still asks
 * the LSP-owned source index for its position, and the two agree while both are
 * refreshed off the same parse. The variants name the resolved declaration and
 * nothing else, so neither read can be short-circuited by a value the
 * resolution happened to have in hand.
 *
 * <p>What a coordinate resolves against is {@link TypeMemberScope}'s, shared with
 * completion, hover's coordinate arm and the field-member diagnostic. The
 * projection answers one question that is left here, and it is not a backing:
 * which Java method a method-backed field binds to, which is the field's own
 * classification.
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

    /** The class a type resolves against, where the store scopes it to a class and not to a table. */
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
     * Resolves the declaration {@code declaration} binds to against the store's own relations, and
     * against {@code built} for the one question the projection still answers. The only
     * tree-sitter-bound step is reading a field's {@code @field(name:)} override off its node; the
     * scope switch itself is the {@link #ofType} / {@link #ofField} core, so the source-index read is
     * left to the per-consumer projection. The store answers both what a coordinate resolves against
     * and what that scope then offers: a table's generated class and columns, a class's member slots,
     * a method's arity. Where the declaration <em>is</em> is still the consumer's own read.
     */
    static DeclTarget resolve(
        SdlDeclaration declaration, LspSchemaSnapshot.Built built,
        StoreHandle store, byte[] source
    ) {
        return switch (declaration) {
            case SdlDeclaration.TypeName t -> ofType(t.typeName(), store);
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

    /**
     * Resolver core for a type-name coordinate: no tree-sitter, no source index, and no projection
     * either. A type scoped to tables names the generated class of the table it resolves against; a
     * type scoped to a class names that class; a type the store scopes to neither names nothing,
     * which is what every unbacked type and every root operation type gets.
     */
    static DeclTarget ofType(String typeName, StoreHandle store) {
        var scope = TypeMemberScope.of(store, typeName);
        if (scope.isEmpty()) return new None();
        return switch (scope.get()) {
            case TypeMemberScope.Scope.Tables tables -> tableTarget(store, tables);
            case TypeMemberScope.Scope.Members(var className) -> new SourceClass(className);
        };
    }

    /**
     * Resolver core for a field-name coordinate, given the already-resolved
     * member name ({@code @field(name:)} override or SDL field name). No
     * tree-sitter, no source index; the store reads are the parent's scope and then, inside it, the
     * column census or the member-slot relation, each of which names the declaration a member binds
     * to without saying where it is written.
     *
     * <p>A member name the scope offers no declaration for resolves to nothing, and that is now the
     * answer for a jOOQ record class no table claims too. The projection routed such a type's field
     * cursor to the backing class instead, because it held no member keys for a record; the class
     * census holds them where the class is a consumer's own, and holds nothing where it is generated,
     * which the catalog census already answers about. So the degrade was standing in for absent
     * facts rather than naming a declaration a field binds to.
     */
    static DeclTarget ofField(
        String parentTypeName, String memberName, LspSchemaSnapshot.Built built, StoreHandle store
    ) {
        // A method-backed field (@service / @externalField / @routine) is
        // bound to its Java method, not to a column on the parent's table, so the
        // classification takes precedence over the parent's scope below.
        var methodBacked = methodBackedTarget(parentTypeName, memberName, built, store);
        if (methodBacked.isPresent()) return methodBacked.get();
        var scope = TypeMemberScope.of(store, parentTypeName);
        if (scope.isEmpty()) return new None();
        return switch (scope.get()) {
            case TypeMemberScope.Scope.Tables tables -> columnTarget(store, tables, memberName);
            case TypeMemberScope.Scope.Members(var className) ->
                memberTarget(store, className, memberName);
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
     * The generated class for the table the parent resolves against. An ambiguous binding takes the
     * first candidate in schema order, as the other surfaces resolving to one declaration do: which
     * one was meant is a resolution question, and a single declaration target cannot hold both.
     */
    private static DeclTarget tableTarget(StoreHandle store, TypeMemberScope.Scope.Tables scope) {
        return CatalogTables.of(store, scope.candidates().getFirst())
            .<DeclTarget>map(table -> new CatalogTable(table.tableName(), table.classFqn()))
            .orElseGet(None::new);
    }

    /**
     * The generated field for a column of one of the tables the parent resolves against, named the
     * way the class declares it: the census carries the SQL name and the jOOQ one, an author may have
     * written either, and what a consumer then reads about the declaration is keyed by the second.
     *
     * <p>Candidates are tried in order and the first that declares the column answers, so an
     * ambiguous binding where only the second table has the column still jumps. That is where a
     * candidate list says more than picking one table up front does: the column named here is
     * evidence about which table the author meant.
     */
    private static DeclTarget columnTarget(
        StoreHandle store, TypeMemberScope.Scope.Tables scope, String memberName
    ) {
        for (var candidate : scope.candidates()) {
            var column = CatalogColumns.of(store, candidate).stream()
                .filter(c -> c.isNamed(memberName))
                .findFirst();
            if (column.isEmpty()) continue;
            var table = CatalogTables.of(store, candidate);
            if (table.isEmpty()) continue;
            return new CatalogColumn(
                table.get().tableName(), table.get().classFqn(), column.get().jooqName());
        }
        return new None();
    }

    /**
     * The declaration behind a member name on a class-scoped type. Which of the two it is follows the
     * slot the store answered with and not the arm that routed it here: a record component is written
     * as a field, a bean accessor as a method, and the relation says which kind the name came from.
     * A name the class offers no slot for resolves to no target.
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
