package no.sikt.graphitron.lsp.facts;

import no.sikt.graphitron.model.read.StoreHandle;
import org.jooq.Condition;
import org.jooq.impl.DSL;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static no.sikt.graphitron.model.Tables.INTENT_CLASS_MEMBER_SLOT;

/**
 * The member names a backing class offers an SDL author: what a {@code @field(name:)} site resolves
 * against on a type backed by a Java class rather than by a table. One query over
 * {@code intent_class_member_slot}, whose own rule decides whether the class answers with record
 * components or with bean accessors.
 *
 * <p>Shared by every surface that asks the question, which is all four of them: completion offers
 * every slot, hover names one, the field-member diagnostic reports a name that matches none, and
 * goto-definition jumps to the declaration behind one. The reason to share is the bean rule, which
 * used to be re-run per build to hand the same list to all four; it now has one home in the DDL and
 * this is the read of it.
 *
 * <p>Ordered by slot name. The census records a declaration position for a record component and
 * none for a method, so the two arms cannot be ordered alike by declaration; a name order is
 * deterministic for both, and no surface ships a sort key anyway, so what an editor shows is its
 * own ordering of the labels.
 *
 * <p>Which class a type is backed by is not this relation's question and is still the LSP snapshot's
 * to answer: the binding is a reflective walk over accessor return types, and the census records
 * those erased, so a container-valued hop has no element type to follow. A caller therefore arrives
 * holding a class name and asks only what the class offers.
 */
public final class ClassMemberSlots {

    private ClassMemberSlots() {}

    /** Every slot the named class offers this graph's census, by slot name. */
    public static List<Slot> of(StoreHandle store, String className) {
        return of(store, className, DSL.noCondition());
    }

    private static List<Slot> of(StoreHandle store, String className, Condition slotFilter) {
        var rows = store.dsl()
            .select(INTENT_CLASS_MEMBER_SLOT.SLOT_NAME, INTENT_CLASS_MEMBER_SLOT.DISPLAY_TYPE,
                INTENT_CLASS_MEMBER_SLOT.ACCESSOR_METHOD_NAME, INTENT_CLASS_MEMBER_SLOT.ORIGIN)
            .from(INTENT_CLASS_MEMBER_SLOT)
            .where(store.reads(INTENT_CLASS_MEMBER_SLOT.SOURCE_NAME))
            .and(INTENT_CLASS_MEMBER_SLOT.CLASS_NAME.eq(className))
            .and(slotFilter)
            .orderBy(INTENT_CLASS_MEMBER_SLOT.SLOT_NAME,
                INTENT_CLASS_MEMBER_SLOT.ACCESSOR_METHOD_NAME)
            .fetch();
        var slots = new ArrayList<Slot>(rows.size());
        for (var row : rows) {
            slots.add(new Slot(row.value1(), row.value2(), row.value3(), Origin.of(row.value4())));
        }
        return slots;
    }

    /**
     * The slot the class offers under {@code slotName}, or empty when it offers none. Exact, never
     * case-insensitive: a member name is a Java identifier the author is naming, not a database
     * coordinate the generator resolves for them, so the classifier that emits the accessor accepts
     * exactly one spelling and so does this. A class spelling one property two ways answers with the
     * first, which is the order a candidate list would have offered them in.
     */
    public static Optional<Slot> named(StoreHandle store, String className, String slotName) {
        var matching = of(store, className, INTENT_CLASS_MEMBER_SLOT.SLOT_NAME.eq(slotName));
        return matching.isEmpty() ? Optional.empty() : Optional.of(matching.getFirst());
    }

    /** Which arm of the rule produced a slot, and the whole of the fork a reader makes on it. */
    public enum Origin {

        /** A component of a record class; its declaration is a field, and its accessor its own name. */
        RECORD_COMPONENT,

        /** A bean accessor on anything else; its declaration is the method the slot was read from. */
        BEAN_ACCESSOR;

        static Origin of(String stored) {
            return switch (stored) {
                case "RECORD_COMPONENT" -> RECORD_COMPONENT;
                case "BEAN_ACCESSOR" -> BEAN_ACCESSOR;
                default -> throw new IllegalStateException(
                    "intent_class_member_slot.origin holds an unknown arm '" + stored
                    + "'; the relation's vocabulary and this decode are one closed set");
            };
        }
    }

    /**
     * One member slot as an editor surface needs it.
     *
     * @param name the name an author writes into {@code @field(name:)}
     * @param displayType the member's type as the census renders it, erased and package-less
     * @param accessorMethodName the Java declaration behind the slot; for a record component its
     *                           own name, for a bean accessor the accessor's
     * @param origin which arm produced it, which is what tells a reader whether the declaration
     *               behind it is a field or a method
     */
    public record Slot(String name, String displayType, String accessorMethodName, Origin origin) {}
}
