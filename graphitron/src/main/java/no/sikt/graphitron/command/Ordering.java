package no.sikt.graphitron.command;

import no.sikt.graphitron.rewrite.model.OrderBySpec;

import java.util.Objects;

/**
 * A launcher's ORDER BY. Two arms, split on the discriminator that carries weight downstream:
 * whether the ordering carries an edge to an emitted method. {@link Columns} is a statically
 * resolved column list rendered inline, no callee anywhere; {@link Helper} dispatches through
 * the emitted {@code <field>OrderBy(env, table)} helper, so the row gains an emitted-method edge
 * the edge view must see. If ordering is ever lifted to a command family of its own (the
 * seam-worklist's ordering row), the arms collapse to one reference honestly. An unordered
 * composition carries no {@code Ordering} at all: the slot is absent, which covers the
 * single-record shape (the model's ordering contract gives single-value fields no spec) and a
 * list over a table with no primary key and no default order.
 *
 * <p>The spec sketch modelled this slot as a reference to the ordering helper alone; the helper
 * exists only for the argument-driven shape, while the dominant root shape (the synthesised
 * primary-key default order) renders inline with no named unit anywhere. The {@link Columns} arm
 * borrows the model's resolved {@link OrderBySpec.Fixed} outright rather than re-minting order
 * terms, per the borrow-don't-copy rule ({@code OrderBySpec} is already on the import-direction
 * borrow dial), and deliberately narrows to the {@code Fixed} arm: unlike the multiset wrap's
 * whole-spec slot, an argument-driven spec is unrepresentable here by type rather than by
 * javadoc note.
 */
public sealed interface Ordering {

    /** A statically resolved column order, rendered inline as {@code table.COL.asc(), ...}. */
    record Columns(OrderBySpec.Fixed spec) implements Ordering {
        public Columns {
            Objects.requireNonNull(spec, "spec");
            if (spec.columns().isEmpty()) {
                throw new IllegalArgumentException(
                    "an empty fixed order is unordered; model it as an absent Ordering, not an empty Columns arm");
            }
        }
    }

    /**
     * A runtime-argument-driven order dispatched through the emitted
     * {@code <field>OrderBy(env, table)} helper, whose ref the producer mints from the naming
     * vocabulary; a plain launcher reads {@code .sortFields()} of its result. The callee is a
     * third edge category, distinct from committed-command callees and external callees: a
     * method the run emits (the fetcher generator's helper emission derives its name from the
     * same scheme) but no command commits, so a closure check over launcher rows must carve it
     * out as emitted-but-uncommitted rather than fail on it.
     */
    record Helper(UnitMethodRef method) implements Ordering {
        public Helper {
            Objects.requireNonNull(method, "method");
        }
    }
}
