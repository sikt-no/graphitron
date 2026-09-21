package no.sikt.graphitron.lsp.facts;

import no.sikt.graphitron.model.read.StoreHandle;
import org.jooq.Condition;
import org.jooq.impl.DSL;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static no.sikt.graphitron.model.Tables.CODE_METHOD;
import static no.sikt.graphitron.model.Tables.CODE_TYPE;
import static no.sikt.graphitron.model.Tables.CODE_TYPE_SLOT;

/**
 * The member names a backing class offers an SDL author: what a {@code @field(name:)} site resolves
 * against on a type backed by a Java class rather than by a table. One query over
 * {@code code_type_slot}, where the reading already decided whether the class answers with record
 * components or with bean accessors.
 *
 * <p>Shared by every surface that asks the question, which is all four of them: completion offers
 * every slot, hover names one, the field-member diagnostic reports a name that matches none, and
 * goto-definition jumps to the declaration behind one. The reason to share is the bean rule, which
 * used to be re-run per build to hand the same list to all four; it now has one home in the
 * reading of the classfiles, and this is the read of what that wrote down.
 *
 * <p>Ordered by slot name. The reading records a declaration position for a record component and
 * none for a method, so the two arms cannot be ordered alike by declaration; a name order is
 * deterministic for both, and no surface ships a sort key anyway, so what an editor shows is its
 * own ordering of the labels.
 *
 * <p>The type beside the name is two key joins away rather than a column here, and that is the
 * relation's shape rather than a cost this reader pays reluctantly: a slot is read by a method,
 * that method's result names a type, and how the type renders is the type's own property.
 *
 * <p>Which class a type is backed by is not this relation's question. It is {@link TypeBackingClass}'s,
 * and a caller arrives here holding a class name and asks only what the class offers.
 */
public final class ClassMemberSlots {

    private ClassMemberSlots() {}

    /** Every slot the named class offers this graph's sources, by slot name. */
    public static List<Slot> of(StoreHandle store, String className) {
        return of(store, className, DSL.noCondition());
    }

    private static List<Slot> of(StoreHandle store, String className, Condition slotFilter) {
        var rows = store.dsl()
            .select(CODE_TYPE_SLOT.SLOT_NAME, CODE_TYPE.DISPLAY_NAME,
                CODE_TYPE_SLOT.METHOD_NAME, CODE_TYPE_SLOT.ORIGIN)
            .from(CODE_TYPE_SLOT)
            .join(CODE_METHOD).on(accessor())
            .join(CODE_TYPE).on(CODE_TYPE.SOURCE_NAME.eq(CODE_METHOD.SOURCE_NAME)
                .and(CODE_TYPE.TYPE_NAME.eq(CODE_METHOD.RESULT_TYPE)))
            .where(store.reads(CODE_TYPE_SLOT.SOURCE_NAME))
            .and(CODE_TYPE_SLOT.CLASS_NAME.eq(className))
            .and(slotFilter)
            .orderBy(CODE_TYPE_SLOT.SLOT_NAME, CODE_TYPE_SLOT.METHOD_NAME)
            .fetch();
        var slots = new ArrayList<Slot>(rows.size());
        for (var row : rows) {
            slots.add(new Slot(row.value1(), row.value2(), row.value3(), Origin.of(row.value4())));
        }
        return slots;
    }

    /**
     * The slot's own accessor: the whole of {@code code_method}'s key, which is the whole of the
     * foreign key the slot hangs on, so the join draws exactly one row and never widens the answer.
     */
    private static Condition accessor() {
        return CODE_METHOD.SOURCE_NAME.eq(CODE_TYPE_SLOT.SOURCE_NAME)
            .and(CODE_METHOD.CLASS_NAME.eq(CODE_TYPE_SLOT.CLASS_NAME))
            .and(CODE_METHOD.METHOD_NAME.eq(CODE_TYPE_SLOT.METHOD_NAME))
            .and(CODE_METHOD.DESCRIPTOR.eq(CODE_TYPE_SLOT.DESCRIPTOR));
    }

    /**
     * The slot the class offers under {@code slotName}, or empty when it offers none. Exact, never
     * case-insensitive: a member name is a Java identifier the author is naming, not a database
     * coordinate the generator resolves for them, so the classifier that emits the accessor accepts
     * exactly one spelling and so does this. A class spelling one property two ways answers with the
     * first, which is the order a candidate list would have offered them in.
     */
    public static Optional<Slot> named(StoreHandle store, String className, String slotName) {
        var matching = of(store, className, CODE_TYPE_SLOT.SLOT_NAME.eq(slotName));
        return matching.isEmpty() ? Optional.empty() : Optional.of(matching.getFirst());
    }

    /** Which arm of the rule produced a slot, and the whole of the fork a reader makes on it. */
    public enum Origin {

        /** A component of a record class; its declaration is a field, and its accessor its own name. */
        RECORD_COMPONENT,

        /** A bean accessor on anything else; its declaration is the method the slot was read from. */
        BEAN_ACCESSOR;

        /**
         * The stored word as an arm, for a reader that selected the column itself. Public because a
         * consumer composing its own statement over this relation still owes the decode to the
         * vocabulary's owner rather than to a switch of its own.
         */
        public static Origin of(String stored) {
            return switch (stored) {
                case "RECORD_COMPONENT" -> RECORD_COMPONENT;
                case "BEAN_ACCESSOR" -> BEAN_ACCESSOR;
                default -> throw new IllegalStateException(
                    "code_type_slot.origin holds an unknown arm '" + stored
                    + "'; the relation's vocabulary and this decode are one closed set");
            };
        }
    }

    /**
     * One member slot as an editor surface needs it.
     *
     * @param name the name an author writes into {@code @field(name:)}
     * @param displayType the member's type with its packages dropped, as the reading renders it
     * @param accessorMethodName the Java declaration behind the slot; for a record component its
     *                           own name, for a bean accessor the accessor's
     * @param origin which arm produced it, which is what tells a reader whether the declaration
     *               behind it is a field or a method
     */
    public record Slot(String name, String displayType, String accessorMethodName, Origin origin) {}
}
