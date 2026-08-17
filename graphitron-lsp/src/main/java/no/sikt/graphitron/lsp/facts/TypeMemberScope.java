package no.sikt.graphitron.lsp.facts;

import no.sikt.graphitron.model.read.StoreHandle;

import java.util.List;
import java.util.Optional;

/**
 * What a member name written inside an SDL type resolves against: the columns of the tables the
 * type is bound to, or the member slots of the class that stands for it. The type grain of the
 * question {@link FieldColumnScope} answers one grain down, and the fall-back that relation's
 * absent row defers to.
 *
 * <p>Shared by every surface that asks it, which is completion, hover and the field-member
 * diagnostic. Each of them used to switch on a classification permit carrying both a table name and
 * a class name, so the fork and both payloads arrived pre-resolved from a reflective walk; the fork
 * is this read now, and its two arms are two relations.
 *
 * <h2>The two arms are read in order, and the order is this reader's</h2>
 *
 * <p>A {@code @table} binding answers first, and a class only where no binding does. That is the
 * classification walk's own precedence, which reads the table and never consults the class, and it
 * is a reading {@code intent_type_backing} deliberately records as a choice rather than folding in:
 * a type its binding and its producers answer differently is two rows there, and preferring one is
 * a consumer's to own.
 *
 * <p>What settles where the choice lives is that the arms share no payload. The backing relation
 * coalesces them by carrying a class name for both, reaching the table arm's through the table's
 * generated record, and a table jOOQ generated no record class for reports the census sentinel
 * instead. Routing the table arm through the class would therefore leave every table-bound type
 * unscoped in any workspace whose catalog was generated without record classes, which is a
 * configuration and not a mistake. So the table arm reads {@link BoundTables} directly, the two
 * arms stay two relations, and the ordering between them is stated here.
 *
 * <h2>A class that is a table's record is a table</h2>
 *
 * <p>Where the class standing for a type turns out to be the class jOOQ binds a table's rows to,
 * the scope is that table: the member names an author writes at such a type are the table's
 * columns, and the class census excludes the generated jOOQ package by design, so reading the class
 * for slots would answer nothing. {@link CatalogTables#ofRecordClass} is the lookup, and a record
 * class no table claims leaves the class arm standing.
 *
 * <h2>Ambiguity is candidates, silence is one answer</h2>
 *
 * <p>A binding two schemas both satisfy contributes both tables rather than declining, which is
 * what the census says and what the surfaces already offered when they read a bare spelling. There
 * is no scope at all for a type nothing binds and no class stands for, and none for a type whose
 * backing is contested, {@link TypeBackingClass} having no answer to give in either case.
 *
 * <p>Two populations have a scope here that the walk calls a carrier and declines: a payload
 * wrapper, which the closure backs with the class its producer returns rather than reaching past it
 * to the wrapped data field, and a single-object field produced by a collection return, the walk's
 * cardinality guard not being a clause of the closure. Both are recorded differences from the walk
 * rather than defects of it, and at an editor surface each costs at most a member list offered
 * where the generator would not have used one.
 */
public final class TypeMemberScope {

    private TypeMemberScope() {}

    /** What member names written inside a type resolve against. */
    public sealed interface Scope permits Scope.Tables, Scope.Members {

        /**
         * The type's fields resolve against these tables' columns; never empty, and longer than one
         * only where the binding itself is ambiguous.
         */
        record Tables(List<CatalogTable> candidates) implements Scope {}

        /** The type's fields resolve against the member slots of this class. */
        record Members(String className) implements Scope {}
    }

    /**
     * The scope for one type, empty where the store binds it to nothing and names no single class
     * for it. Empty is also what the surfaces render as silence, an editor asking what a type offers
     * having nothing to say about a type that offers nothing it can see.
     */
    public static Optional<Scope> of(StoreHandle store, String typeName) {
        var bound = BoundTables.of(store, typeName);
        if (!bound.isEmpty()) {
            return Optional.of(new Scope.Tables(bound));
        }
        return TypeBackingClass.of(store, typeName).map(className -> {
            var tables = CatalogTables.ofRecordClass(store, className);
            return tables.isEmpty()
                ? new Scope.Members(className)
                : new Scope.Tables(tables.stream().map(CatalogTables.Table::key).toList());
        });
    }
}
