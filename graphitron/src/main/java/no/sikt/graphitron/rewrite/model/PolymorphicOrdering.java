package no.sikt.graphitron.rewrite.model;

import no.sikt.graphitron.model.jooq.ColumnRef;

import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.SequencedMap;

/**
 * The ordering of a multi-table polymorphic root ({@link QueryField.QueryInterfaceField} /
 * {@link QueryField.QueryUnionField}), lowered onto the {@code UNION ALL} of its participant branches.
 *
 * <p>Almost all of an ordering is one field-level decision written once in the SDL: the argument's
 * name and shape, the set and order of named orders, each entry's direction and collation, and
 * whether an order is uniformly ascending. Only the column an entry resolves to is per participant.
 * So the field-level half is stated here once, and the per-participant resolution hangs off the
 * {@link Slot}s the branches project: a slot is one alias in every branch, typed once, carrying one
 * column per participant. An {@link SlotOrder} is what the SDL declared, pointing at slots by ordinal.
 * The cross-participant agreement the emitter rests on (same slots, same order, compatible types on
 * every branch) therefore holds by construction rather than as an agreement between independently
 * resolved copies.
 *
 * <p>Not an {@link OrderBySpec} arm: no arm of that type can carry a per-participant column map.
 *
 * <p>Every primary-key-shaped order ({@code @order(primaryKey: true)},
 * {@code @defaultOrder(primaryKey: true)}, and the implicit fallback) is an
 * {@link SlotOrder.OnSyntheticKey}: it projects no slot and orders by the synthetic key every branch
 * already projects, which is the same order.
 *
 * @param participants the table-bound participants every slot is total over, in branch order
 * @param surface      where the ordering is chosen: fixed at build time, or by an argument at runtime
 * @param slots        the projected order columns; a slot's index in this list is its ordinal
 * @param namedOrders  the argument's named orders in declaration order; empty on {@link Surface.Fixed}
 * @param base         the {@code @defaultOrder}, or the primary-key fallback
 */
public record PolymorphicOrdering(
    List<ParticipantRef.TableBound> participants,
    Surface surface,
    List<Slot> slots,
    List<SlotOrder> namedOrders,
    SlotOrder base
) {
    public PolymorphicOrdering {
        participants = List.copyOf(participants);
        Objects.requireNonNull(surface, "surface");
        slots = List.copyOf(slots);
        namedOrders = List.copyOf(namedOrders);
        Objects.requireNonNull(base, "base");
        if (base.name() != null) {
            throw new IllegalArgumentException("the base order carries no name, got '" + base.name() + "'");
        }
        if (surface instanceof Surface.Fixed && !namedOrders.isEmpty()) {
            throw new IllegalArgumentException("a fixed ordering carries no named orders");
        }
        for (var order : namedOrders) {
            if (order.name() == null) {
                throw new IllegalArgumentException("a named order carries its enum value name");
            }
        }
        var participantSet = new HashSet<>(participants);
        for (int i = 0; i < slots.size(); i++) {
            var slot = slots.get(i);
            if (slot.ordinal() != i) {
                throw new IllegalArgumentException(
                    "slot at position " + i + " carries ordinal " + slot.ordinal());
            }
            if (!slot.columnByParticipant().keySet().equals(participantSet)
                    || slot.columnByParticipant().size() != participants.size()) {
                throw new IllegalArgumentException(
                    "slot " + i + " is not total over the participant set");
            }
        }
        for (var order : allOrders(namedOrders, base)) {
            if (order instanceof SlotOrder.OnSlots onSlots) {
                for (var entry : onSlots.entries()) {
                    if (entry.slotOrdinal() < 0 || entry.slotOrdinal() >= slots.size()) {
                        throw new IllegalArgumentException(
                            "order entry names slot " + entry.slotOrdinal() + ", which does not exist");
                    }
                }
            }
        }
    }

    private static List<SlotOrder> allOrders(List<SlotOrder> namedOrders, SlotOrder base) {
        var all = new java.util.ArrayList<SlotOrder>(namedOrders);
        all.add(base);
        return all;
    }

    /** Where the ordering is chosen. */
    public sealed interface Surface {

        /** Fixed at build time: a {@code @defaultOrder}, or the primary-key fallback. */
        record Fixed() implements Surface {}

        /**
         * Chosen at runtime by an {@code @orderBy} argument over an {@code @order} enum. The same
         * field-level facts {@link OrderBySpec.Argument} carries, minus the per-table resolutions.
         */
        record Argument(
            String name,
            String typeName,
            boolean nonNull,
            boolean list,
            String sortFieldName,
            String directionFieldName
        ) implements Surface {}
    }

    /**
     * One projected order column: one alias in every {@code UNION ALL} branch and one column per
     * participant. A slot's identity is its whole per-participant column map, so two order entries
     * share a slot exactly when they resolve to the same column on every participant.
     *
     * @param ordinal             the slot's position in {@link #slots()}, which names its alias
     * @param slotClass           the one bound Java type every participant's column agrees on
     * @param columnByParticipant the column each participant projects into the slot; the first
     *                            entry's column types the emitted slot field
     */
    public record Slot(
        int ordinal,
        String slotClass,
        SequencedMap<ParticipantRef.TableBound, ColumnRef> columnByParticipant
    ) {
        public Slot {
            Objects.requireNonNull(slotClass, "slotClass");
            if (columnByParticipant.isEmpty()) {
                throw new IllegalArgumentException("a slot projects at least one participant's column");
            }
            columnByParticipant = Collections.unmodifiableSequencedMap(new LinkedHashMap<>(columnByParticipant));
        }

        /** The first participant's column, whose {@code DataType} the emitted slot field is typed by. */
        public java.util.Map.Entry<ParticipantRef.TableBound, ColumnRef> typingColumn() {
            return columnByParticipant.firstEntry();
        }
    }

    /** One declared order: a named order of the argument's enum, or the base. */
    public sealed interface SlotOrder {

        /** The enum value name; {@code null} on the base. */
        String name();

        /**
         * Whether every entry is ascending, in which case a runtime {@code direction:} flips the
         * whole order, tiebreakers included; any non-ascending entry locks the order's directions.
         */
        boolean uniformAsc();

        /** An order over projected slots. */
        record OnSlots(String name, List<SlotEntry> entries, boolean uniformAsc) implements SlotOrder {
            public OnSlots {
                entries = List.copyOf(entries);
                if (entries.isEmpty()) {
                    throw new IllegalArgumentException("an order over slots names at least one slot");
                }
            }
        }

        /** A primary-key-shaped order: it orders by the synthetic key and projects no slot. */
        record OnSyntheticKey(String name, OrderBySpec.SortDirection direction, boolean uniformAsc)
                implements SlotOrder {
            public OnSyntheticKey {
                Objects.requireNonNull(direction, "direction");
            }
        }
    }

    /**
     * One entry of an {@link SlotOrder.OnSlots}: the slot it sorts on, with the direction and
     * collation the SDL declared. Stated once per entry, so a per-participant disagreement on either
     * is unrepresentable. {@code collation} is carried and, as on every other ordering path, not
     * emitted.
     */
    public record SlotEntry(int slotOrdinal, OrderBySpec.SortDirection direction, String collation) {
        public SlotEntry {
            Objects.requireNonNull(direction, "direction");
        }
    }
}
