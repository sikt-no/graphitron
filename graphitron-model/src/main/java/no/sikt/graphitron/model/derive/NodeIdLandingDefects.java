package no.sikt.graphitron.model.derive;

import graphql.language.SourceLocation;
import no.sikt.graphitron.model.diagnostics.Rejection;
import no.sikt.graphitron.model.diagnostics.ValidationError;
import org.jooq.Condition;
import org.jooq.DSLContext;

import java.util.Arrays;
import java.util.List;

import static no.sikt.graphitron.model.Tables.INTENT_NODE_ID_DECODE_LANDING_DEFECT;
import static no.sikt.graphitron.model.Tables.INTENT_TYPE_DOMAIN;
import static no.sikt.graphitron.model.derive.NodeIdMessages.inputFieldLead;
import static no.sikt.graphitron.model.derive.NodeIdMessages.nodeIdSpelling;
import static no.sikt.graphitron.model.derive.NodeIdMessages.simpleName;
import static org.jooq.impl.DSL.exists;
import static org.jooq.impl.DSL.selectOne;

/**
 * Where a decoding {@code @nodeId} filter's key actually lands, checked rather than assumed:
 * {@code intent_node_id_decode_landing_defect} projected into the two refusals a mislanded key
 * earns, and the prose each carries.
 *
 * <p>Landing is the per-position fact the classifier computes for such a filter, which column of
 * the filtered row each value of the target node type's key binds against, and it is the whole of
 * the arm choice between a local tuple comparison and a correlated {@code EXISTS}. It was computed
 * by matching SQL column names along the path's foreign-key hops and never verified, so two things
 * went unchecked. That the path's last hop reaches the node type's table at all: a path stopping one
 * hop short lands the key on whatever the intermediate table happens to have named like it. And
 * that the two columns a landed position pairs agree on the Java type jOOQ binds them as: matching
 * by name alone counted a converter-backed column and a raw one as the same column.
 *
 * <p>Both were accepted with zero errors, and what an author met instead differed by what the
 * terminal table happened to hold. A column of that name and type is generated Java that compiles
 * and SQL that may or may not be the filter they wrote; no such column, or one of another type, is
 * generated Java that does not compile. The coincidence is why this is a verification gap rather
 * than a codegen crash: nothing separated "graphitron checked that this predicate binds the columns
 * the author meant" from "it happened to", so all of it refuses now.
 *
 * <p>The routes are the view's population and not this reader's choice. A decode reaches its target
 * along an {@code @reference} path the author wrote, across the single foreign key auto-discovery
 * finds, or by own-row identity, and the first two are judged; identity has nothing to land. A
 * per-participant {@code @referenceFor} route is a fourth spelling of the first that the endpoint
 * family does not model, so a branch such a route applies at is declined rather than judged, on the
 * terms {@code intent_node_id_decode_landing_defect} states.
 *
 * <p>The population is narrowed once more here, on {@link NodeIdDecodeDefects}'s terms: this is the
 * <em>build-error</em> consumer, so it joins {@code intent_type_domain} on the consuming
 * coordinate's owning type. Only a coordinate the generator intends to classify can fail a build.
 * That is a different gate from the view's own population and the two do not collide: the
 * population decides which endpoints have a landing worth judging, and the domain membership
 * decides which of the resulting rows can fail a build. A row the population keeps and this gate
 * drops is a true defect at a coordinate nothing generates, which is what an editor arm reading the
 * view ungated wants.
 *
 * <p>Both rejections are {@link Rejection.AuthorError.Structural}, as the sibling {@code @nodeId}
 * families' are: neither names a closed set an editor could offer alternatives from. Neither is a
 * deferral either, and the type verdict's remedy is deliberately two remedies rather than a
 * {@code forcedType}: a divergence can be accidental, one physical column given a converter on one
 * of its tables for an unrelated reason, or intended, a code table typing its own key differently
 * from the tables referencing it. A message naming one fix would be wrong half the time.
 */
public final class NodeIdLandingDefects {

    private NodeIdLandingDefects() {}

    /** What the store can show is wrong with the landing, in the view's own closed vocabulary. */
    private enum Verdict {
        /**
         * The authored path's last hop arrives on a table other than the one the node type is
         * bound to. Fires only where the chain is whole, a chain that stalled on an unknown or
         * ambiguous key already being the walk's rejection at the same coordinate.
         */
        PATH_STOPS_SHORT,
        /**
         * Every key position landed on a column of the filtered row and one of those pairs
         * disagrees on the Java type jOOQ binds them as, so no predicate can compare one against
         * the other. Fires only on a landing that is total, which is the local tuple comparison
         * the verdict is about: a position landing nowhere sends the whole endpoint to a
         * correlated {@code EXISTS} on the node type's own table, where a landed position's
         * column appears in no comparison and its type can disagree for free. Excluded where the
         * same branch draws the verdict above: on a mislanded path the column the lift matched by
         * name is not the node's key column at all, and one fault draws one row.
         */
        LANDING_TYPE_DISAGREEMENT;

        /** The verdict a store row carries; an unknown value is vocabulary drift, a build bug. */
        static Verdict of(String verdict) {
            return Arrays.stream(values())
                .filter(v -> v.name().equals(verdict))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                    "the node id landing defect view produced verdict '" + verdict + "', which no "
                    + Verdict.class.getSimpleName()
                    + " value names; the view arms and the enum must move together"));
        }
    }

    /**
     * The detection pass's typed product: one entry per refused landing, use site and branch.
     * {@link #violations()} is the error stream every caller reads; the entries are kept beside it
     * so a consumer wanting the coordinates and the columns has them without re-parsing a message.
     */
    public record Detection(List<Defect> defects) {

        public Detection {
            defects = List.copyOf(defects);
        }

        /** The empty detection, for callers running capture without the detection pass. */
        public static Detection empty() {
            return new Detection(List.of());
        }

        /** Every violation the detection minted, in coordinate order. */
        public List<ValidationError> violations() {
            return defects.stream()
                .map(d -> ValidationError.forField(d.coordinate(), d.rejection(), d.location()))
                .toList();
        }
    }

    /**
     * One refused landing: the consuming coordinate the error attaches to, the slot the author
     * annotated, the node type it decodes against, the branch the decode departs, and the witness
     * columns the message quotes. Every witness rides the record because a consumer grouping
     * refusals by the table or column that could not bind would otherwise recover it from prose;
     * the ones a verdict has no operand for are {@code null}, which the verdict's own arm decides.
     */
    public record Defect(String coordinate, String slot, String nodeTypeName, String originTable,
                         String terminalTable, String targetTable, Integer position,
                         String keyColumnName, String keyBindingType,
                         String localColumnName, String localBindingType,
                         Rejection rejection, SourceLocation location) {}

    /**
     * Projects every refused landing over {@code graphName}'s emitted partition. Empty for a graph
     * whose decoding {@code @nodeId} filters all land their target node type's key on columns of
     * that type's own table, and for one whose refusals all sit outside the classification domain.
     */
    public static Detection detect(DSLContext dsl, String graphName) {
        var v = INTENT_NODE_ID_DECODE_LANDING_DEFECT;
        return new Detection(dsl.selectFrom(v)
            .where(v.GRAPH_NAME.eq(graphName), inDomain(graphName))
            .orderBy(v.ROOT_TYPE_NAME, v.ROOT_FIELD_NAME, v.USE_SITE, v.ORIGIN_TABLE, v.VERDICT,
                v.POSITION)
            .fetch(row -> new Defect(
                row.getRootTypeName() + "." + row.getRootFieldName(),
                slot(row.getSite(), row.getTypeName(), row.getFieldName(), row.getArgumentName()),
                row.getNodeTypeName(), row.getOriginTable(),
                row.getTerminalTable(), row.getTargetTable(), row.getPosition(),
                row.getKeyColumnName(), row.getKeyBindingType(),
                row.getLocalColumnName(), row.getLocalBindingType(),
                rejectionOf(Verdict.of(row.getVerdict()), lead(row.getSite(), row.getTypeName(),
                        row.getFieldName(), row.getArgumentName(), row.getOriginTable(),
                        row.getBranches()),
                    row.getNodeTypeName(), row.getTerminalTable(), row.getTargetTable(),
                    row.getKeyColumnName(), row.getKeyBindingType(),
                    row.getOriginTable(), row.getLocalColumnName(), row.getLocalBindingType()),
                location(row.getSourceName(), row.getSourceLine(), row.getSourceColumn()))));
    }

    /**
     * The build-error consumer's population: the consuming coordinate's owning type is a member of
     * the classification domain. The refused slot sits under that type's field, whether as its
     * argument or as an input field the argument's surface reaches, so the field's population is
     * its type's, which is the one predicate the sibling family uses at both its grains.
     */
    private static Condition inDomain(String graphName) {
        var d = INTENT_TYPE_DOMAIN;
        var v = INTENT_NODE_ID_DECODE_LANDING_DEFECT;
        return exists(selectOne().from(d)
            .where(d.GRAPH_NAME.eq(graphName), d.TYPE_NAME.eq(v.ROOT_TYPE_NAME)));
    }

    /**
     * Decodes one verdict into the rejection the report carries. Each message states the operands
     * the view compared and nothing it did not: the path verdict quotes the two tables off the row
     * and the type verdict both columns and both types, so neither can describe a comparison other
     * than the one that refused the landing.
     */
    private static Rejection rejectionOf(Verdict verdict, String lead, String nodeTypeName,
                                         String terminalTable, String targetTable,
                                         String keyColumnName, String keyBindingType,
                                         String originTable, String localColumnName,
                                         String localBindingType) {
        return switch (verdict) {
            case PATH_STOPS_SHORT -> Rejection.structural(lead + ": "
                + nodeIdSpelling(nodeTypeName) + " reaches its target through an @reference path"
                + " whose last step lands on table '" + terminalTable + "', not on " + nodeTypeName
                + "'s @table '" + targetTable + "'. A decoded " + nodeTypeName + " id binds against"
                + " columns of '" + targetTable + "', so the path has to end there: add the"
                + " remaining step, or name the type the path already reaches");
            case LANDING_TYPE_DISAGREEMENT -> Rejection.structural(lead + ": "
                + nodeIdSpelling(nodeTypeName) + " decodes key column '" + keyColumnName
                + "' of table '" + targetTable + "', which jOOQ binds as "
                + simpleName(keyBindingType) + ", and the reference path lands it on column '"
                + localColumnName + "' of table '" + originTable + "', which jOOQ binds as "
                + simpleName(localBindingType) + ". The two columns disagree on Java type, so no"
                + " predicate can bind one against the other. Either align the two columns' catalog"
                + " types, or route the reference through a path that lands the key on a column of"
                + " its own type");
        };
    }

    /**
     * The coordinate lead both verdicts open with: the slot the author annotated, and the branch it
     * decodes on where the coordinate has several. The branch is the departing table, and it is
     * named only where there is a choice: a slot under a multi-table polymorphic root decodes once
     * per branch and one branch can mislead where its sibling does not, so a message that named no
     * branch there would leave an author guessing which participant it is about.
     */
    private static String lead(String site, String typeName, String fieldName,
                               String argumentName, String originTable, int branches) {
        return slot(site, typeName, fieldName, argumentName)
            + (branches > 1 ? " on the '" + originTable + "' branch" : "");
    }

    /**
     * How the annotated slot is named, by site. The argument spelling is the one the sibling
     * {@code @nodeId} families already use; the input-field one is minted in
     * {@link NodeIdMessages} so the next family with both sites reads it rather than re-minting it.
     * The error itself attaches to the consuming coordinate rather than to the slot, because the
     * landing is a fact of the use site: one input field is reached from two queries whose scope
     * tables differ, so it can land right at one and wrong at the other.
     */
    private static String slot(String site, String typeName, String fieldName,
                               String argumentName) {
        return "ARGUMENT".equals(site)
            ? "argument '" + argumentName + "'"
            : inputFieldLead(typeName, fieldName);
    }

    /** The store's position columns as a graphql-java location; {@code null} when unpositioned. */
    private static SourceLocation location(String sourceName, Integer line, Integer column) {
        if (line == null || column == null) {
            return null;
        }
        return new SourceLocation(line, column, sourceName);
    }
}
