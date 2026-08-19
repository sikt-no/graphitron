package no.sikt.graphitron.lsp.parsing;

import io.github.treesitter.jtreesitter.Node;
import no.sikt.graphitron.lsp.facts.DeclarationFacts;
import no.sikt.graphitron.lsp.facts.FieldProducerMethods;
import no.sikt.graphitron.lsp.facts.TypeMemberScope;

import java.util.Optional;

/**
 * The Java declaration an SDL <em>declaration-name</em> coordinate (a type-name
 * or field-name token, the trigger {@link SdlDeclaration} owns) binds to, named
 * independently of how a consumer projects it. Goto-definition projects each
 * variant to a source {@code Location}; the declaration-name hover arm projects
 * each to a Javadoc overlay.
 *
 * <p>Sharing the resolution is what keeps the two consumers pointing at one
 * declaration: a single scope-switch ({@link #of}) produces the target,
 * and each consumer switches over the <em>same</em> {@code DeclTarget}
 * exhaustively, so a new {@link TypeMemberScope.Scope} arm breaks both switches
 * at compile time. What differs is only which fact each then reads about the
 * declaration, and both read it out of the same {@link DeclarationFacts} rows,
 * one for a doc comment and one for a position. The variants name the resolved
 * declaration and nothing else, so neither read can be short-circuited by a
 * value the resolution happened to have in hand.
 *
 * <p>What a coordinate resolves against is {@link TypeMemberScope}'s, shared with
 * completion, hover's coordinate arm and the field-member diagnostic. Which Java method a
 * method-backed field binds to is {@link FieldProducerMethods}', for {@code @service} and
 * {@code @externalField}, and the catalog census's own, for the generated call surface a
 * {@code @routine} read or write binds to. Every arm resolves with no build behind it.
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
     * The store key a declaration name is asking about: a type name, or a field's parent type and the
     * member the field binds to. The only tree-sitter-bound step in the whole resolution, and it is
     * here rather than inside it so a consumer knows the whole coordinate before it reads anything:
     * every arm of {@link DeclarationFacts} is keyed on this, and one statement can only be issued by
     * a caller that already holds the key.
     */
    static DeclarationFacts.Coord coordinateOf(SdlDeclaration declaration, byte[] source) {
        return switch (declaration) {
            case SdlDeclaration.TypeName t -> new DeclarationFacts.Coord.Type(t.typeName());
            case SdlDeclaration.FieldName f -> {
                // The bound member is named by the field's @field(name:) override
                // when it carries one, else by the SDL field name itself. The
                // field-definition node is the declaration name's parent.
                Node fieldDef = f.nameNode().getParent().orElse(null);
                String memberName = fieldDef == null
                    ? f.fieldName()
                    : effectiveMemberName(fieldDef, f.fieldName(), source);
                yield new DeclarationFacts.Coord.Member(f.parentTypeName(), memberName);
            }
        };
    }

    /**
     * Resolves the declaration a coordinate binds to over rows a caller already holds. Nothing here
     * reads anything: a consumer that fetched {@link DeclarationFacts} for this coordinate has paid
     * for the whole resolution already.
     */
    static DeclTarget of(DeclarationFacts.Coord coord, DeclarationFacts.Rows rows) {
        return switch (coord) {
            case DeclarationFacts.Coord.Type ignored -> ofType(rows);
            case DeclarationFacts.Coord.Member ignored -> ofField(rows);
        };
    }

    /**
     * Resolver core for a type-name coordinate: no tree-sitter, no query, and no projection either. A
     * type scoped to tables names the generated class of the table it resolves against; a type scoped
     * to a class names that class; a type the store scopes to neither names nothing, which is what
     * every unbacked type and every root operation type gets.
     */
    static DeclTarget ofType(DeclarationFacts.Rows rows) {
        var scope = rows.scope();
        if (scope.isEmpty()) return new None();
        return switch (scope.get()) {
            case TypeMemberScope.Scope.Tables tables -> tableTarget(rows, tables);
            case TypeMemberScope.Scope.Members(var className) -> new SourceClass(className);
        };
    }

    /**
     * Resolver core for a field-name coordinate, over the same rows. The populations are the parent's
     * scope and then, inside it, the columns the member name reached or the member slot it names, each
     * of which names the declaration a member binds to without saying where it is written.
     *
     * <p>A member name the scope offers no declaration for resolves to nothing, and that is now the
     * answer for a jOOQ record class no table claims too. The projection routed such a type's field
     * cursor to the backing class instead, because it held no member keys for a record; the class
     * census holds them where the class is a consumer's own, and holds nothing where it is generated,
     * which the catalog census already answers about. So the degrade was standing in for absent
     * facts rather than naming a declaration a field binds to.
     */
    static DeclTarget ofField(DeclarationFacts.Rows rows) {
        // A method-backed field (@service / @externalField / @routine) is
        // bound to its Java method, not to a column on the parent's table, so the
        // producer takes precedence over the parent's scope below.
        var produced = rows.producer();
        if (produced.isPresent()) {
            var producer = produced.get();
            return new SourceMethod(producer.fqClassName(), producer.methodName(), producer.arity());
        }
        var routine = rows.routineMethod();
        if (routine.isPresent()) {
            var call = routine.get();
            return new SourceMethod(call.className(), call.methodName(), call.arity());
        }
        var scope = rows.scope();
        if (scope.isEmpty()) return new None();
        return switch (scope.get()) {
            case TypeMemberScope.Scope.Tables tables -> columnTarget(rows, tables);
            case TypeMemberScope.Scope.Members(var className) -> memberTarget(rows, className);
        };
    }

    /**
     * The generated class for the table the parent resolves against. An ambiguous binding takes the
     * first candidate in schema order, as the other surfaces resolving to one declaration do: which
     * one was meant is a resolution question, and a single declaration target cannot hold both.
     */
    private static DeclTarget tableTarget(
        DeclarationFacts.Rows rows, TypeMemberScope.Scope.Tables scope
    ) {
        return rows.table(scope.candidates().getFirst())
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
        DeclarationFacts.Rows rows, TypeMemberScope.Scope.Tables scope
    ) {
        for (var candidate : scope.candidates()) {
            var column = rows.column(candidate);
            if (column.isEmpty()) continue;
            var table = rows.table(candidate);
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
    private static DeclTarget memberTarget(DeclarationFacts.Rows rows, String fqClassName) {
        return rows.slot(fqClassName)
            .<DeclTarget>map(slot -> switch (slot.origin()) {
                case RECORD_COMPONENT -> new SourceField(fqClassName, slot.slotName());
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
