package no.sikt.graphitron.rewrite.model;

import java.util.Objects;

/**
 * The per-coordinate delivery fact: does this coordinate's SQL composition split from its
 * parent's statement into a batched keyed re-query ({@link Batched}), or does its value arrive
 * without a split statement of its own ({@link Inline})? The coordinate fact behind
 * anchor-hood: a child coordinate launches its own query unit exactly when its delivery is
 * batched, and the launcher membership predicate is that view joined with the members the
 * launch hosts.
 *
 * <p>{@link Inline} is the complement, and it deliberately covers two situations that agree on
 * the falsifiable content (no split SQL statement arrives batched): a coordinate riding its
 * parent's statement (a correlated multiset, a spliced nesting projection, a column term), and
 * a coordinate with no SQL statement at all (a {@code @service} invocation's delegation, a
 * record read off a held producer record). A {@code @service} coordinate reads {@link Inline}
 * even though its loader is batched, because the serviceCall member owns that delivery the same
 * way it claims the projection slot; the launch derives from the member, never from this axis.
 *
 * <p>{@link Batched}'s trigger is the sealed provenance disjunction, the arm's falsifiable
 * content: an authored marker ({@link Trigger.Authored}), a record-handing parent
 * ({@link Trigger.RecordHandedParent}), or the list-valued polymorphic fan-in
 * ({@link Trigger.PolymorphicFanIn}). The first two were bound at the keystone; the third was
 * surfaced by this fact's materialization (the batched polymorphic pair mints from a
 * cardinality-plus-participant rule that neither named trigger covers), which is the trigger
 * disjunction doing its falsifiable-content job.
 */
public sealed interface DeliveryFact {

    /** The coordinate's own SQL splits from the parent statement into a batched keyed re-query. */
    record Batched(Trigger trigger) implements DeliveryFact {
        public Batched {
            Objects.requireNonNull(trigger, "trigger");
        }
    }

    /** No split statement of its own arrives batched; the complement (see the class javadoc). */
    record Inline() implements DeliveryFact {
        public static final Inline INSTANCE = new Inline();
    }

    /** What names the batched delivery: the provenance disjunction, one arm per trigger fact. */
    sealed interface Trigger {

        /** An authored delivery marker ({@code @splitQuery}; {@code @tenantFanOut} on a table child). */
        record Authored() implements Trigger {
            public static final Authored INSTANCE = new Authored();
        }

        /**
         * The parent hands a domain record, so there is no parent statement to ride: a
         * table-bound child re-queries batched, keyed off the record.
         */
        record RecordHandedParent() implements Trigger {
            public static final RecordHandedParent INSTANCE = new RecordHandedParent();
        }

        /**
         * A list-valued polymorphic target with a table-bound participant: no single-table
         * correlated multiset exists for the per-participant UNION assembly, so the delivery
         * splits regardless of markers or the parent's backing.
         */
        record PolymorphicFanIn() implements Trigger {
            public static final PolymorphicFanIn INSTANCE = new PolymorphicFanIn();
        }
    }

    /**
     * The leaf-derived crosswalk: the delivery each classified leaf encodes, total over the
     * sealed hierarchies with no default. This is the comparison side of the delivery pin and
     * the walk-less-schema fallback behind the schema view, never a production source; it is
     * also where a new leaf is forced to declare its delivery arm at compile time, the first
     * half of the enforcer that replaced the launcher producer's leaf-identity membership
     * switches. Unlike the member crosswalk this is not a window shim with an expiry date:
     * delivery is one of the three axes the leaf reconstruction key keeps, so the crosswalk
     * survives the dissolution slices as the leaf encoding's reader.
     */
    static DeliveryFact leafDerivedOf(OutputField leaf) {
        return switch (leaf) {
            case ChildField.BatchedTableField f -> new Batched(f.sourceShape() == SourceShape.Record
                ? Trigger.RecordHandedParent.INSTANCE : Trigger.Authored.INSTANCE);
            case ChildField.BatchedLookupTableField f -> new Batched(f.sourceShape() == SourceShape.Record
                ? Trigger.RecordHandedParent.INSTANCE : Trigger.Authored.INSTANCE);
            // The batched pivot's only mint gate is the authored marker; @pivot rejects
            // record-backed parents, so the record-handed trigger cannot arise.
            case ChildField.BatchedPivotField _ -> new Batched(Trigger.Authored.INSTANCE);
            // The pair mints from the cardinality-plus-participant rule on table and record
            // parents alike (the record-parent arm applies the same rule), so the fan-in
            // trigger is the provenance regardless of the parent's backing.
            case ChildField.BatchedInterfaceField _, ChildField.BatchedUnionField _ ->
                new Batched(Trigger.PolymorphicFanIn.INSTANCE);
            case ChildField.ColumnBackedField _, ChildField.ColumnBackedReferenceField _,
                 ChildField.ParticipantColumnReferenceField _, ChildField.TableField _,
                 ChildField.LookupTableField _, ChildField.TableInterfaceField _,
                 ChildField.InterfaceField _, ChildField.UnionField _, ChildField.NestingField _,
                 ChildField.PivotField _, ChildField.PivotSlotField _,
                 ChildField.ServiceTableField _, ChildField.ServiceRecordField _,
                 ChildField.RecordReadField _, ChildField.RecordCompositeField _,
                 ChildField.ComputedField _, ChildField.SingleRecordIdField _,
                 ChildField.SingleRecordIdFieldFromReturning _, ChildField.ErrorsField _ ->
                Inline.INSTANCE;
            // Roots are the entry points: nothing arrives, so nothing splits.
            case QueryField _, MutationField _ -> Inline.INSTANCE;
        };
    }
}
