package no.sikt.graphitron.rewrite.model;

import no.sikt.graphitron.render.CatalogRefs;
import no.sikt.graphitron.model.jooq.ColumnRef;

/**
 * Where a record-read leaf's value is located on the parent's in-memory source object. One
 * non-null arm per read mechanism, replacing the nullable column/accessor slot pair the
 * pre-merge leaves carried: read sites switch on the arm identity instead of null-checking
 * slots, and the parent {@link GraphitronType.ResultType} is consulted only for the cast
 * target (the source object gates and casts, the locator locates).
 *
 * <p>Arm admissibility is gated by the parent type's source-object shape, a cross-axis
 * invariant the leaf cannot see alone: {@link TypedColumn} only under a
 * {@link GraphitronType.JooqTableRecordType} parent with a resolved table,
 * {@link JavaAccessor} only under a class-backed parent
 * ({@link GraphitronType.JavaRecordType} / {@link GraphitronType.PojoResultType.Backed}),
 * {@link ByName} only under a {@link GraphitronType.JooqRecordCarrier} parent. Checked by
 * {@code GraphitronSchemaValidator}'s record-read rule, which is what lets the emitter's
 * per-arm casts assume the parent shape without defensive guards.
 *
 * <p>The axis is deliberately partial against the full locator family:
 * {@link ChildField.NestingField}'s identity read (passthrough) and
 * {@link ChildField.Transport}'s localContext arms are the same axis under other names and
 * are expected to converge onto it in a follow-up. Until then a component on the single
 * record-read leaf is the correct home; the read sites fork on locator identity, a sealed
 * switch, not a capability.
 */
public sealed interface ValueLocator {

    /**
     * The parent is a {@link GraphitronType.JooqTableRecordType} whose table resolved the
     * read name to a real column: the read is the typed-constant form
     * {@code record.get(Tables.X.COL)}, and {@code CatalogRefs.columnType(column)} answers the leaf's
     * domain return type.
     */
    record TypedColumn(ColumnRef column) implements ValueLocator {
        public TypedColumn {
            java.util.Objects.requireNonNull(column, "column");
        }
    }

    /**
     * Class-backed parent (Java record component, getter, or public field): the read goes
     * through the pre-resolved accessor handle. Classifier-side rejection routes through
     * {@link GraphitronField.UnclassifiedField} at classify time, so this arm only ever
     * carries a {@link AccessorResolution.Resolved}.
     */
    record JavaAccessor(AccessorResolution.Resolved accessor) implements ValueLocator {
        public JavaAccessor {
            java.util.Objects.requireNonNull(accessor, "accessor");
        }
    }

    /**
     * Untyped by-name read {@code record.get(DSL.field(sqlName))} off a
     * {@link GraphitronType.JooqRecordCarrier} parent: the nesting-reuse case where no typed
     * {@code Tables.X.COL} constant resolves (a non-table-bound jOOQ record, or a table-record
     * parent whose catalog lookup found no matching column).
     */
    record ByName(String sqlName) implements ValueLocator {
        public ByName {
            java.util.Objects.requireNonNull(sqlName, "sqlName");
        }
    }

    /**
     * Graphitron locates nothing; graphql-java's default property machinery reads the named
     * property off the source object. Home of the two populations that resolve no column and
     * no accessor without being {@link ByName}: fields on {@code @error}-type parents (whose
     * parent is not a {@link GraphitronType.ResultType} at all; their read is mediated by
     * {@link GraphitronType.ErrorType#accessorBaseFor} against the developer's exception
     * class) and class-backed parents whose backing class could not be loaded.
     */
    record DefaultRead(String name) implements ValueLocator {
        public DefaultRead {
            java.util.Objects.requireNonNull(name, "name");
        }
    }
}
