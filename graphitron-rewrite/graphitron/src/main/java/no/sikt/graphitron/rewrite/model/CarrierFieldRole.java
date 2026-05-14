package no.sikt.graphitron.rewrite.model;

/**
 * Sealed enumeration of the carrier field roles a single-record carrier admits. R141 permits
 * exactly {@link DataChannel} (the list- or single-shaped read-back of the produced rows) and
 * {@link ErrorChannelRole} (R12's {@code errors: [SomeError!]!} field whose binding is the
 * resolved {@link ErrorChannel} record).
 *
 * <p>Future Backlog items ({@code payload-carrier-affected-row-count},
 * {@code payload-carrier-client-mutation-id}) admit new sibling-field shapes by adding new
 * {@code CarrierFieldRole} permits and tightening {@link SingleRecordCarrierShape}'s
 * compact-constructor invariant. The "exactly the admitted roles, nothing else" invariant is
 * a type-system statement: a carrier field that does not resolve to a permit causes the
 * carrier classifier to reject the carrier-returning mutation field, not the carrier to
 * resolve with the unknown field tolerated.
 */
public sealed interface CarrierFieldRole {

    /** The SDL field name on the carrier type. */
    String fieldName();

    /**
     * The data-channel field: the SDL field whose element type is paired to the producing
     * operation's {@code @table} (or, for the {@code @service} carrier path, the method's
     * reflected record return). The {@link DataElement} arm discriminates the element kind;
     * the wrapper lives on the element arm.
     *
     * <p>R159 — {@code sourceSigil} carries the carrier walk's parse decision for the
     * data field's optional {@code @field(name: "$source")} directive: {@code true} when
     * the author opted into the upstream-root binding sigil, {@code false} for the
     * implicit binding (no {@code @field} directive, or {@code @field} with a bare name).
     * The bit is read downstream by the type-match validator in {@code FieldBuilder} so
     * the SDL directive is parsed exactly once at carrier-walk time. Always {@code false}
     * for {@link DataElement.Id} carriers (the sigil is not admitted there).
     */
    record DataChannel(String fieldName, DataElement element, boolean sourceSigil) implements CarrierFieldRole {}

    /**
     * The error-channel field: the carrier-side {@code errors: [SomeError!]!} field whose
     * binding is the R12-produced {@link ErrorChannel} record (payload class, errors-slot
     * index, defaulted slots, mappings constant). R141 wraps R12's existing record without
     * modifying it; the resolution flows from the shared errors-field detector that both the
     * carrier walk's role-permit classifier rule and the four non-carrier
     * {@code resolveErrorChannel} callers consume.
     */
    record ErrorChannelRole(String fieldName, ErrorChannel binding) implements CarrierFieldRole {}
}
