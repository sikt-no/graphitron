package no.sikt.graphitron.rewrite.model;

import java.util.List;

/**
 * An implementing or member type of an interface or union.
 *
 * <p>Variants:
 * <ul>
 *   <li>{@link TableBound} — the participant's data lives wholly on one table (the shared
 *       interface/base table for single-table discriminated inheritance, or its own independent
 *       PK-bearing table for multi-table polymorphism). Carries an optional discriminator value
 *       and an optional cross-table-field list.</li>
 *   <li>{@link JoinedTableBound} — a joined-table (class-table) inheritance participant: a
 *       discriminated base shared with its siblings plus the participant's own detail table,
 *       joined to the base by the author-declared child&rarr;parent {@code @reference} (PK=FK).
 *       Carries that resolved hop; field residence is declared by the per-field {@code @reference}
 *       and read off the field variant, not stored here. Only ever appears in a
 *       {@link GraphitronType.TableInterfaceType} participant list (R389).</li>
 *   <li>{@link Unbound} — the participant is not table-backed (e.g. {@code @error} types,
 *       structural interfaces, value types). Generator code must skip SQL generation for
 *       unbound participants.</li>
 * </ul>
 *
 * <p>{@link TableBound} and {@link JoinedTableBound} share the {@link TableBacked} capability so
 * sites that only need the participant's type name, table, and discriminator value (TypeResolver
 * routing, discriminator-value collection) read them uniformly without distinguishing single-table
 * from joined-table participants. The emitter switches on the concrete variant where the join shape
 * differs.
 */
public sealed interface ParticipantRef permits ParticipantRef.TableBacked, ParticipantRef.Unbound {

    /** The simple GraphQL type name (e.g. {@code "Film"}). */
    String typeName();

    /**
     * Capability shared by every table-backed participant ({@link TableBound} and
     * {@link JoinedTableBound}). Exposes the participant's own table and its discriminator value so
     * routing / discriminator-collection sites read them without a per-variant switch.
     *
     * <p>For {@link TableBound} the table is the single table the participant's data lives on; for
     * {@link JoinedTableBound} it is the participant's detail table. {@code discriminatorValue} is
     * the {@code @discriminator(value:)} on the participant type, or {@code null} when absent
     * (only single-table {@link TableBound} multi-table participants leave it null; a
     * {@link JoinedTableBound} always carries one).
     */
    sealed interface TableBacked extends ParticipantRef permits TableBound, JoinedTableBound {
        TableRef table();
        String discriminatorValue();
    }

    /**
     * A single-table participant.
     *
     * <p>{@code table} is always non-null. {@code discriminatorValue} is the value from
     * {@code @discriminator(value:)} on this type, or {@code null} when the directive is absent.
     * Only populated for {@link GraphitronType.TableInterfaceType} participants; always {@code null}
     * for plain {@link GraphitronType.InterfaceType} (multi-table) participants.
     *
     * <p>{@code crossTableFields} lists the participant's fields whose value lives on a different
     * table than the participant's own ({@code @reference}-traversed). Used by the
     * {@code TableInterfaceType} fetcher to emit conditional LEFT JOINs gated by the participant's
     * discriminator value, so non-matching rows carry NULL for the cross-table columns. Empty for
     * participants without cross-table fields, and always empty for {@link GraphitronType.InterfaceType}
     * / {@link GraphitronType.UnionType} participants (multi-table polymorphism does not project
     * cross-table fields through this path).
     */
    record TableBound(String typeName, TableRef table, String discriminatorValue,
                      List<CrossTableField> crossTableFields)
            implements TableBacked {

        public TableBound {
            crossTableFields = List.copyOf(crossTableFields);
        }

        /** Convenience constructor for participants with no cross-table fields. */
        public TableBound(String typeName, TableRef table, String discriminatorValue) {
            this(typeName, table, discriminatorValue, List.of());
        }

        /**
         * A participant field whose value lives on a different table than the participant's own.
         * The {@code TableInterfaceType} fetcher emits a conditional LEFT JOIN gated by the
         * participant's discriminator value, projects {@link #column} aliased as {@link #aliasName},
         * and a per-field fetcher reads it back from the result {@code Record} by that alias.
         *
         * <p>{@code hop} is the single-hop {@code @reference} from the interface table to
         * the cross table (exposed via {@link #targetTable()}). Its {@code sourceColumns} sit on
         * the interface table (FK holder) and its {@code targetColumns} sit on the referenced
         * side; the generator builds the JOIN ON condition by equating the two arity-1 column
         * lists.
         */
        public record CrossTableField(
            String fieldName,
            ColumnRef column,
            JoinStep.Hop hop,
            String aliasName
        ) {
            public CrossTableField {
                if (!(hop.on() instanceof On.ColumnPairs)) {
                    throw new IllegalArgumentException(
                        "CrossTableField.hop must be FK-derived (On.ColumnPairs); got " + hop.on());
                }
            }
            /** The FK-derived column pairs of the single cross-table hop. */
            public On.ColumnPairs pairs() { return (On.ColumnPairs) hop.on(); }
            /** The cross table joined to project this field — equivalent to {@code hop().targetTable()}. */
            public TableRef targetTable() { return hop.targetTable(); }

            /** Java variable name used in the generated interface fetcher to hold the aliased target table. */
            public String aliasVarName() { return aliasName + "_alias"; }
        }
    }

    /**
     * A joined-table (class-table) inheritance participant (R389).
     *
     * <p>The participant shares a discriminated base table with its sibling participants (one PK
     * space, one discriminator column) and declares its own detail table via {@code @table}. Its
     * fields split across the two tables, and residence is <em>declared</em> by the author rather
     * than inferred: a field carrying a parent-{@code @reference} back to the base is base-resident
     * (classifies as {@link ChildField.ColumnReferenceField}, resolved on the base via the existing
     * reference path); a field without one is detail-resident (a plain {@link ChildField.ColumnField}
     * on this detail table). No site recomputes residence; the emitter and validator read the field
     * variant.
     *
     * <p>{@code detailTable} is the participant's own table (the {@code table()} capability returns
     * it). {@code discriminatorValue} is the participant's {@code @discriminator(value:)}, always
     * non-null (a {@code TableInterfaceType} participant without one is rejected upstream).
     *
     * <p>{@code childToParent} is the resolved single-hop FK-derived {@link JoinStep.Hop} the author named in
     * the inherited fields' parent-{@code @reference}: {@code originTable} is the detail table,
     * {@code targetTable} the discriminated base. The hop is direction-blind ({@code slot.sourceSide()}
     * sits on the detail/FK side, {@code slot.targetSide()} on the base/PK side), so the standalone
     * reference path reads it as {@code detail -> base} while the interface fetcher reads the same slot
     * pair as {@code base.pk = detail.fk} for the discriminator-gated {@code base LEFT JOIN detail},
     * carrying NULL through for non-matching rows. The PK=FK invariant (the detail's FK columns to the
     * base are the detail's own primary key) is checked by the validator, not encoded here.
     *
     * <p>There is no residence list on this record: the interface fetcher emits a <em>detail-only</em>
     * projection (the participant's detail-resident {@link ChildField.ColumnField}s against the detail
     * alias, parent-references not re-traversed), while the participant's own {@code $fields} stays
     * whole and serves standalone queries (parent-references traversed to the base). The two are
     * distinct projections, never one globally-restricted method.
     */
    record JoinedTableBound(String typeName, TableRef detailTable, String discriminatorValue,
                            JoinStep.Hop childToParent)
            implements TableBacked {

        public JoinedTableBound {
            if (!(childToParent.on() instanceof On.ColumnPairs)) {
                throw new IllegalArgumentException(
                    "JoinedTableBound.childToParent must be FK-derived (On.ColumnPairs); got "
                    + childToParent.on());
            }
        }

        /** The FK-derived column pairs of the child->parent hop. */
        public On.ColumnPairs childToParentPairs() { return (On.ColumnPairs) childToParent.on(); }

        /** The participant's detail table. Satisfies {@link TableBacked#table()}. */
        @Override public TableRef table() { return detailTable; }

        /** Java variable name used in the generated interface fetcher to hold the aliased detail table. */
        public String detailAliasVarName() { return typeName + "_detail_alias"; }

        /** Stable alias string for the detail table within the interface query. */
        public String detailAliasName() { return typeName + "_detail"; }
    }

    /**
     * A non-table-backed participant.
     *
     * <p>Used for {@code @error} types, structural interfaces, and other types that carry no
     * jOOQ table. Generator switches must handle this variant and skip SQL-emitting paths.
     */
    record Unbound(String typeName) implements ParticipantRef {}
}
