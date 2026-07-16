package no.sikt.graphitron.rewrite.generators;

import no.sikt.graphitron.rewrite.GraphitronSchema;
import no.sikt.graphitron.rewrite.model.BatchKeyField;
import no.sikt.graphitron.rewrite.model.ChildField;
import no.sikt.graphitron.rewrite.model.ColumnRef;
import no.sikt.graphitron.rewrite.model.GraphitronField;
import no.sikt.graphitron.rewrite.model.SourceKey;
import no.sikt.graphitron.rewrite.model.SourceShape;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.Set;

/**
 * The parent-projection containment invariant (R333 "Query anchors and the two flows", shipped by
 * R432): <em>when a child's key tuple is lifted off the parent's held object, the parent anchor's
 * projection must contain the key columns.</em> R425 is the shipped bug that shows what its
 * absence costs — a pattern-match omission inside the projection walk left a DataLoader key
 * column out of the parent SELECT, surfacing as a silent {@code null} key at runtime; the level-1
 * closure oracle (method-name resolution) cannot see it because the projection is a value set,
 * not a name binding.
 *
 * <p>Two walks are cross-checked at the {@code $fields} emit site
 * ({@link TypeClassGenerator#generateForType}):
 *
 * <ul>
 *   <li><b>Guarantee</b> — the {@link TypeClassGenerator.RequiredProjection} that
 *       {@code TypeClassGenerator.collectRequiredProjection} computed for the anchor type (the
 *       walk under audit).</li>
 *   <li><b>Requirement</b> — this class's own enumeration of every table-sourced
 *       {@link BatchKeyField} coordinate rooted at the anchor: the anchor's own entries from the
 *       classifier's flat field index ({@link GraphitronSchema#fields()}), descending
 *       {@link ChildField.NestingField} sub-trees with a local worklist (nested plain-object
 *       fields are not flat-indexed; they resolve through the embedding {@code NestingField}).
 *       Each such coordinate's {@code sourceKey()} demand must be contained in the guarantee:
 *       base-named columns for the column-tuple wraps, the reserved full parent row for
 * {@link SourceKey.Wrap.TableRecord}.</li>
 * </ul>
 *
 * <p><b>Independence is the hard requirement, not a preference.</b> The requirement side must not
 * call {@code collectRequiredProjection} or borrow its {@code NestingField} recursion or
 * {@code fieldsOf} locality: R425's omission was <em>inside</em> that walk, and a requirement
 * side sharing its traversal would reproduce the omission on both sides and pass green over the
 * exact bug family this check exists to catch. It is keyed on the {@link BatchKeyField}
 * capability plus {@link ChildField#sourceShape()}, never on leaf identity, so the R432 leaf
 * merge does not touch it. Record-sourced coordinates stay out: their key rides the held object,
 * not the parent SELECT (the projection walk's hard-throw tripwire owns that exclusion).
 *
 * <p><b>A divergence is a generator invariant violation, not an author-facing rejection.</b> No
 * valid author schema can produce it under a correct generator — the demand and the projection
 * are both derived from the same classified model, so disagreement always means a walk omission
 * (a Graphitron bug). {@link IllegalStateException} at generation time is therefore deliberate;
 * the "validator mirrors classifier" rejection discipline does not call for a typed
 * {@code Rejection} here, and this check must not be re-homed into the validator.
 */
final class ParentProjectionContainmentCheck {

    private ParentProjectionContainmentCheck() {}

    /**
     * Cross-checks {@code guaranteed} (the audited walk's output for {@code anchorTypeName})
     * against this class's own demand enumeration. Throws {@link IllegalStateException} on the
     * first demanded column (or reserved-full-row requirement) the projection does not contain.
     */
    static void check(GraphitronSchema schema, String anchorTypeName,
                      TypeClassGenerator.RequiredProjection guaranteed) {
        Set<ColumnRef> guaranteedColumns = new HashSet<>(guaranteed.baseColumns());
        Deque<GraphitronField> pending = new ArrayDeque<>();
        for (GraphitronField f : schema.fields().values()) {
            if (anchorTypeName.equals(f.parentTypeName())) {
                pending.add(f);
            }
        }
        while (!pending.isEmpty()) {
            GraphitronField f = pending.poll();
            if (f instanceof ChildField.NestingField nf) {
                pending.addAll(nf.nestedFields());
                continue;
            }
            if (!(f instanceof BatchKeyField bk) || bk.sourceKey() == null) {
                continue;
            }
            if (!(f instanceof ChildField cf) || cf.sourceShape() != SourceShape.Table) {
                continue;
            }
            if (bk.sourceKey().wrap() instanceof SourceKey.Wrap.TableRecord) {
                if (!guaranteed.reservedFullRow()) {
                    throw new IllegalStateException(
                        "Graphitron generator bug (parent-projection containment, R425 family): field '"
                            + f.parentTypeName() + "." + f.name() + "' keys its DataLoader batch on the typed"
                            + " parent record (SourceKey.Wrap.TableRecord), which requires the reserved"
                            + " full parent row in type '" + anchorTypeName + "'s $fields SELECT, but the"
                            + " projection walk (TypeClassGenerator.collectRequiredProjection) did not"
                            + " flip reservedFullRow. The requirement walk (this check, over the"
                            + " classified field index) found a demand the guarantee walk missed — a"
                            + " projection-walk omission, not a schema authoring error.");
                }
            } else {
                for (ColumnRef col : bk.sourceKey().columns()) {
                    if (!guaranteedColumns.contains(col)) {
                        throw new IllegalStateException(
                            "Graphitron generator bug (parent-projection containment, R425 family): field '"
                                + f.parentTypeName() + "." + f.name() + "' is DataLoader-backed off a table"
                                + " parent and its key extraction reads column '" + col.sqlName() + "' off"
                                + " the parent row, but the projection walk"
                                + " (TypeClassGenerator.collectRequiredProjection) for type '"
                                + anchorTypeName + "' did not include it in the $fields SELECT. The"
                                + " requirement walk (this check, over the classified field index) found a"
                                + " demand the guarantee walk missed — a projection-walk omission, not a"
                                + " schema authoring error.");
                    }
                }
            }
        }
    }
}
