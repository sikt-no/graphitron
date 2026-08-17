package no.sikt.graphitron.lsp.facts;

import no.sikt.graphitron.model.read.StoreHandle;

import java.util.Optional;

import static no.sikt.graphitron.model.Tables.INTENT_COLUMN_MATCH_CLAIM;
import static no.sikt.graphitron.model.Tables.INTENT_RESOLVED_FIELD_CLAIM;

/**
 * Which member of its parent's scope a field's own name reaches, spelled as an author would write it
 * into {@code @field(name:)}. The field grain of the question {@link TypeMemberScope} answers one
 * grain up: that relation says what names resolve inside a type, this one says which of them this
 * field reached.
 *
 * <h2>Two arms, and the order between them is this reader's</h2>
 *
 * <p>A column answers first. {@code intent_column_match_claim} carries the match rule itself, both
 * naming tiers and the table the site navigates to, so where it holds a row the store has already
 * resolved the name and nothing here re-runs it. Read through {@code intent_resolved_field_claim}
 * rather than raw: a coordinate an authored directive claims is one the generator reads no column
 * at, and the raw reading surviving in the classifier view is there for a surface explaining the
 * override rather than for one naming the member.
 *
 * <p>A class member answers where no column does and the parent's scope is a class's. Which slots a
 * class offers is {@code intent_class_member_slot}'s rule, record components or bean accessors by the
 * class's own declared form, and {@link ClassMemberSlots#named} is the exact-spelling read of it that
 * the generator's accessor emission agrees with.
 *
 * <h2>One population has no answer, and the absence is the store's rather than this reader's</h2>
 *
 * <p>A type nothing binds, whose backing class turns out to be a table's row type, scopes to that
 * table, and a name written there resolves against its columns; {@link TypeMemberScope} says so and
 * offers them. The column arm cannot reach that coordinate: {@code intent_field_column_scope} derives
 * a site's table from a {@code @table} binding or from an authored path and never from the parent's
 * backing class, so the match is not derived there at all. Empty, which a caller renders as nothing
 * rather than as a resolution. Closing it is a rule in that relation, not a lookup here: resolving
 * the name against the table would put the match rule in a consumer beside the one the store owns.
 */
public final class FieldMemberName {

    private FieldMemberName() {}

    /**
     * The member name {@code Type.field} resolves to, empty where the store resolves none. Empty is
     * also what a coordinate outside the graph gives, the two being one answer for a caller that
     * renders a resolution and has none.
     */
    public static Optional<String> of(StoreHandle store, String typeName, String fieldName) {
        var column = matchedColumn(store, typeName, fieldName);
        if (column.isPresent()) return column;
        var scope = TypeMemberScope.of(store, typeName).orElse(null);
        if (!(scope instanceof TypeMemberScope.Scope.Members members)) return Optional.empty();
        return ClassMemberSlots.named(store, members.className(), fieldName)
            .map(ClassMemberSlots.Slot::name);
    }

    /** The column the reduction settled on at this coordinate, the classifier's own witness. */
    private static Optional<String> matchedColumn(StoreHandle store, String typeName, String fieldName) {
        var claim = INTENT_COLUMN_MATCH_CLAIM;
        var resolved = INTENT_RESOLVED_FIELD_CLAIM;
        return Optional.ofNullable(store.dsl()
            .select(claim.COLUMN_NAME)
            .from(claim)
            .join(resolved)
            .on(resolved.GRAPH_NAME.eq(claim.GRAPH_NAME))
            .and(resolved.TYPE_NAME.eq(claim.TYPE_NAME))
            .and(resolved.FIELD_NAME.eq(claim.FIELD_NAME))
            .and(resolved.CLASSIFIER.eq(claim.CLASSIFIER))
            .where(claim.GRAPH_NAME.eq(store.graphName()))
            .and(claim.TYPE_NAME.eq(typeName))
            .and(claim.FIELD_NAME.eq(fieldName))
            .fetchOne(claim.COLUMN_NAME));
    }
}
