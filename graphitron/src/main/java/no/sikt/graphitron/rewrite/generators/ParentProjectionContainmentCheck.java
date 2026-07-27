package no.sikt.graphitron.rewrite.generators;

import no.sikt.graphitron.rewrite.GraphitronSchema;
import no.sikt.graphitron.rewrite.model.BatchKeyField;
import no.sikt.graphitron.rewrite.model.ChildField;
import no.sikt.graphitron.rewrite.model.ColumnRef;
import no.sikt.graphitron.rewrite.model.GraphitronField;
import no.sikt.graphitron.rewrite.model.ParentRowDemand;
import no.sikt.graphitron.rewrite.model.SourceShape;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The parent-projection containment invariant: <em>when a child's key tuple is lifted off the
 * parent's held object, the parent anchor's projection must contain the key columns.</em> A
 * shipped bug shows what its absence costs: a pattern-match omission inside the projection walk
 * left a DataLoader key
 * column out of the parent SELECT, surfacing as a silent {@code null} key at runtime; the level-1
 * closure oracle (method-name resolution) cannot see it because the projection is a value set,
 * not a name binding.
 *
 * <p>Two walks are cross-checked at the {@code $fields} emit site
 * ({@link TypeClassGenerator#generateForType}):
 *
 * <ul>
 *   <li><b>Guarantee</b>: the base-named column list that
 *       {@code TypeClassGenerator.collectRequiredProjection} computed for the anchor type (the
 *       walk under audit).</li>
 *   <li><b>Requirement</b>: this class's own enumeration of every table-sourced
 *       {@link BatchKeyField} and {@link ParentRowDemand} coordinate rooted at the anchor,
 *       read off the classifier's flat field index ({@link GraphitronSchema#fields()}) and
 *       descending {@link ChildField.NestingField} sub-trees with a local worklist (nested
 *       plain-object fields are not flat-indexed). Each coordinate's demanded columns must be
 *       contained in the guarantee, every key wrap alike.</li>
 * </ul>
 *
 * <p><b>Independence is the hard requirement, not a preference.</b> The requirement side must not
 * call {@code collectRequiredProjection} or borrow its {@code NestingField} recursion or
 * {@code fieldsOf} locality: that bug's omission was <em>inside</em> that walk, and a requirement
 * side sharing its traversal would reproduce the omission on both sides and pass green over the
 * exact bug family this check exists to catch. It is keyed on the {@link BatchKeyField} /
 * {@link ParentRowDemand} capabilities plus {@link ChildField#sourceShape()}, never on leaf
 * identity. Record-sourced coordinates stay out: their key / correlation rides the held object,
 * not the parent SELECT (the projection walk's hard-throw tripwire owns that exclusion).
 *
 * <p><b>A divergence is a generator invariant violation, not an author-facing rejection.</b> No
 * valid author schema can produce it under a correct generator: the demand and the projection
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
     * first demanded column the projection does not contain.
     */
    static void check(GraphitronSchema schema, String anchorTypeName,
                      List<ColumnRef> guaranteed) {
        Set<ColumnRef> guaranteedColumns = new HashSet<>(guaranteed);
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
            // Both capability enumerations are gated on a table-backed source shape: a
            // record-sourced coordinate keys / correlates off the held object, not the parent
            // SELECT (the projection walk's hard-throw tripwire owns that exclusion).
            if (!(f instanceof ChildField cf) || cf.sourceShape() != SourceShape.Table) {
                continue;
            }
            // Every key wrap demands the same thing: its SourceKey columns, present in the parent
            // SELECT under their base names. Wrap.TableRecord is not special here: its columns
            // are the parent's primary key, which buildKeyExtraction reads by field identity.
            if (f instanceof BatchKeyField bk && bk.sourceKey() != null) {
                for (ColumnRef col : bk.sourceKey().columns()) {
                    if (!guaranteedColumns.contains(col)) {
                        throw new IllegalStateException(
                            "Graphitron generator bug (parent-projection containment): field '"
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
            // ParentRowDemand widens the same containment invariant to correlation reads: a
            // table-parent child whose fetcher reads parent-row columns by base name (a
            // a multi-table polymorphic single-fetch parent-side read,
            // or a batched polymorphic key extraction) demands every such column be projected.
            if (f instanceof ParentRowDemand prd) {
                for (ColumnRef col : prd.parentRowColumns()) {
                    if (!guaranteedColumns.contains(col)) {
                        throw new IllegalStateException(
                            "Graphitron generator bug (parent-projection containment): field '"
                                + f.parentTypeName() + "." + f.name() + "' reads parent-row column '"
                                + col.sqlName() + "' off the parent record (ParentRowDemand), but the"
                                + " projection walk (TypeClassGenerator.collectRequiredProjection) for type '"
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
