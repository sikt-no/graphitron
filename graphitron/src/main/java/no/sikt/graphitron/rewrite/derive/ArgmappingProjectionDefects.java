package no.sikt.graphitron.rewrite.derive;

import graphql.language.SourceLocation;
import no.sikt.graphitron.rewrite.ArgMappingSigil;
import no.sikt.graphitron.rewrite.ValidationError;
import no.sikt.graphitron.rewrite.model.Rejection;
import org.jooq.DSLContext;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import static no.sikt.graphitron.model.Tables.INTENT_ARGMAPPING_PAIR;
import static no.sikt.graphitron.model.Tables.INTENT_ARGMAPPING_PROJECTION_DEFECT;
import static no.sikt.graphitron.model.Tables.INTENT_RESOLVED_NODE_KEY_PROJECTION;
import static no.sikt.graphitron.rewrite.derive.NodeIdMessages.keyColumnsOf;
import static no.sikt.graphitron.rewrite.derive.NodeIdMessages.nodeIdSpelling;
import static no.sikt.graphitron.rewrite.derive.NodeIdMessages.simpleName;

/**
 * The {@code @nodeId} key-projection rules for {@code argMapping}, projected from the store: an
 * {@code argMapping} path that binds a node id either projects one of that node type's key columns
 * or is one of the defects below, and this class derives the located {@link ValidationError} values
 * for both halves. The reduction lives in the SQL, in
 * {@code intent_argmapping_projection_defect} and the projection relation beside it; what remains
 * here is the decode of the view's closed {@code verdict} vocabulary into {@link Rejection} arms and
 * the prose those arms carry.
 *
 * <p>Six arms are the author's to fix, and they close the silence this family had: binding a
 * {@code @nodeId} without naming a key column used to hand a routine parameter or a service method
 * the base64 wire id verbatim, and nothing in the build said a word. Which arm fires is the view's
 * decision, taken on whether the leaf declares a decode, then on the trailing-segment count, then on
 * whether a candidate column exists, so nothing here re-tests a predicate the query already settled.
 *
 * <p>Not naming a key column is only a defect where there is nothing to infer. A node type keyed on
 * one column has exactly one thing such a binding could project, so the store resolves it as a
 * projection and the emitter decodes it, which is why {@code BARE_NODE_ID}'s three clauses are the
 * three ways an inference has no answer rather than a single statement that the wire value escapes.
 * The safety that lifting the rejection rests on is not this class's leniency but the two arms that
 * still fire: a type disagreement at the inferred column refuses, and a resolved projection at a
 * site no emitter reads defers. Neither lets base64 through.
 *
 * <p>That includes the two arms a reader might expect the schema walk to own. An {@code ID} carrying
 * no {@code @nodeId} has nothing to open, and more than one segment past a node id resolves nothing,
 * but both are questions about captured directive facts and the walk runs before capture. So the walk
 * carries every segment it cannot resolve against SDL and judges none of them, and
 * {@code UNDECLARED_NODE_ID} and {@code TRAILING_SEGMENTS_BEYOND_ONE} are where those rules live. A
 * rule spelled in the walk instead would be an earlier second copy that wins by rejecting first,
 * which is how one family ends up with two answers that agree until one changes.
 *
 * <p>Two further arms are the generator's rather than the author's, which is why they are derived
 * here and not in SQL: a projection that resolves at a site whose emitter does not read it yet, and
 * one off a list-shaped node id, are both {@link Rejection.Deferred}. Whether an emitter exists is a
 * fact about this codebase and not about the schema. {@link #EMITTING_SITES} is the first of those
 * facts, held beside the switch that names the eight sites so a value can neither be misspelled nor
 * forgotten; the second is the list shape, a coherent request naming the list of a key column across
 * the decoded ids that nothing builds yet. Both shrink as emitters land rather than being deleted,
 * and a projection either arm covers fails the build saying so, which is the honest state: emitting
 * nothing, or emitting the raw base64, are the two outcomes they exist to prevent.
 *
 * <p>The message vocabulary is shared with {@link NodeIdDecodeDefects}, which refuses the same two
 * facts one carrier over, where a producer parameter's name matches the {@code @nodeId} argument
 * instead of an entry here binding it. {@link NodeIdMessages} holds what must not drift between them;
 * the remedies differ, and differ because the carrier does.
 *
 * <p>Locations are the view's: the owning directive application's own position, so a message points
 * at the {@code argMapping} the author wrote rather than at the input type's declaration. The
 * message names the use site whenever that says more than the coordinate the error already carries,
 * which is what makes a definition-keyed remedy actionable: one input type can be consumed where
 * inference works and where it cannot, and an author told to add {@code typeName:} needs to know
 * which consumer is asking.
 */
public final class ArgmappingProjectionDefects {

    private ArgmappingProjectionDefects() {}

    /**
     * The {@code site} values whose emitters read a resolved key projection: a routine IN parameter and
     * a {@code @condition} method parameter, both reading their column off a decoded record through
     * {@link no.sikt.graphitron.render.ProjectedKeyReads}. The two condition sites are one emitter, the
     * conditions class's glue, which is why they were wired together rather than one at a time.
     *
     * <p>{@code SERVICE} joins when its emitter lands. The input-field {@code @condition} stays out for
     * a different reason worth stating: its pair rows are keyed by the input type and input field, while
     * the condition row rendering it is keyed by the consuming output field, so the projection relation's
     * coordinate never matches and the lookup misses by construction rather than by omission. The three
     * path-step sites resolve no leaf at all and can therefore only ever defer.
     *
     * <p>The set is keyed on the site and so says nothing about whether every <em>emitter</em> at a
     * wired site reads a projection. That second question is the plan's, asked as row presence in
     * {@code EmitPlan}: a projection at a coordinate no wired emitter owns fails the build there
     * rather than being emitted as an ordinary nested read. The two gates are complementary, and
     * neither subsumes the other, because this one runs before any plan exists and that one cannot
     * see the directive a pair was spelled on.
     */
    private static final Set<Site> EMITTING_SITES =
        EnumSet.of(Site.ROUTINE, Site.FIELD_CONDITION, Site.ARGUMENT_CONDITION);

    /**
     * The eight {@code site} values {@code intent_argmapping_pair} discriminates on, each mapped to
     * the {@link ArgMappingSigil.Site} whose description names the directive in a message. The
     * mapping is many-to-one in both directions of reading: three SDL positions share
     * {@code @condition} and the store tells them apart because their heads and their emitters
     * differ, which is exactly the distinction {@link #EMITTING_SITES} needs and the coarser
     * vocabulary cannot express.
     */
    private enum Site {
        ROUTINE(ArgMappingSigil.Site.ROUTINE),
        SERVICE(ArgMappingSigil.Site.SERVICE),
        FIELD_CONDITION(ArgMappingSigil.Site.CONDITION),
        INPUT_FIELD_CONDITION(ArgMappingSigil.Site.CONDITION),
        ARGUMENT_CONDITION(ArgMappingSigil.Site.CONDITION),
        FIELD_REFERENCE_STEP(ArgMappingSigil.Site.REFERENCE_STEP),
        ARGUMENT_REFERENCE_STEP(ArgMappingSigil.Site.REFERENCE_STEP),
        REFERENCE_FOR_STEP(ArgMappingSigil.Site.REFERENCE_STEP);

        private final ArgMappingSigil.Site sigilSite;

        Site(ArgMappingSigil.Site sigilSite) {
            this.sigilSite = sigilSite;
        }

        /** The directive as a message names it, borrowed rather than re-spelled here. */
        String description() {
            return sigilSite.description();
        }

        /** The site a store row names; an unknown value is vocabulary drift, a build bug. */
        static Site of(String site) {
            return Arrays.stream(values())
                .filter(s -> s.name().equals(site))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                    "the argMapping relations produced site '" + site + "', which no "
                    + Site.class.getSimpleName()
                    + " value names; the view arms and the enum must move together"));
        }
    }

    /** Which defect, in the view's own closed vocabulary. */
    private enum Verdict {
        /**
         * A path opening something with nothing to open: an ID carrying no {@code @nodeId}, and
         * equally a String or an enum. The walk carries such a segment rather than rejecting it,
         * because deciding it needs the directive facts only capture holds, so this arm is where the
         * whole rule lives.
         */
        UNDECLARED_NODE_ID,
        /**
         * A declared decode with no key column named after it and none to infer: no node type
         * named, no key resolved for the one that is, or a key of more than one column, where one
         * binding carries one value. A one-column key is not here, the sole column being the only
         * projection such a binding could mean, and it resolves instead.
         */
        BARE_NODE_ID,
        /**
         * More names following the single key column a node id opens into: a typo, or a nested form
         * nothing resolves.
         */
        TRAILING_SEGMENTS_BEYOND_ONE,
        /** A projection asked for against a {@code @nodeId} that names no node type. */
        MISSING_TYPE_NAME,
        /** A trailing segment naming no key column the node type resolved. */
        UNKNOWN_KEY_COLUMN,
        /**
         * A key column whose Java type the consuming parameter cannot take, whether a trailing
         * segment named that column or a one-column key inferred it. The one verdict whose two
         * operands are both types rather than names, which is why it carries them: without both,
         * the message would say a correct column name is wrong. The inferred half is why lifting
         * the bare rejection at arity 1 adds no silence: what stops being a defect for having named
         * no column is still a defect for handing a parameter a value it cannot take.
         */
        KEY_COLUMN_TYPE_MISMATCH;

        /** The verdict a store row carries; an unknown value is vocabulary drift, a build bug. */
        static Verdict of(String verdict) {
            return Arrays.stream(values())
                .filter(v -> v.name().equals(verdict))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                    "the argMapping projection defect view produced verdict '" + verdict
                    + "', which no " + Verdict.class.getSimpleName()
                    + " value names; the view arms and the enum must move together"));
        }
    }

    /**
     * The detection pass's typed product: one entry per rejected {@code argMapping} pair, author
     * defects and unwired-site deferrals alike. {@link #violations()} is the error stream every
     * caller reads; the entries are kept beside it so a consumer wanting the coordinates without
     * re-parsing a message has them.
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
     * One rejected pair: the coordinate the error attaches to, the use site whose constraint it
     * violated, and the typed rejection. {@code deferred} tells the generator's own gap from the
     * author's mistake without a consumer having to test the {@link Rejection} arm.
     */
    public record Defect(String coordinate, String useSite, String paramName, String argumentPath,
                         boolean deferred, Rejection rejection, SourceLocation location) {}

    /**
     * Projects every {@code argMapping} node-id rejection over {@code graphName}'s partition: the
     * six author defects from the detection view, then the deferrals for projections that resolve and
     * cannot be emitted. Empty for a graph whose {@code argMapping} paths all bind ordinary values,
     * and whose projections all resolve at sites that emit them in a shape those emitters build.
     */
    public static Detection detect(DSLContext dsl, String graphName) {
        var defects = new ArrayList<Defect>(authorDefects(dsl, graphName));
        defects.addAll(unemittableProjections(dsl, graphName));
        return new Detection(defects);
    }

    /** The view's six author arms, decoded. */
    private static List<Defect> authorDefects(DSLContext dsl, String graphName) {
        var v = INTENT_ARGMAPPING_PROJECTION_DEFECT;
        return dsl.selectFrom(v)
            .where(v.GRAPH_NAME.eq(graphName))
            .orderBy(v.TYPE_NAME, v.FIELD_NAME, v.USE_SITE, v.POSITION)
            .fetch(row -> {
                var site = Site.of(row.getSite());
                var entry = entry(site, row.getUseSite(), row.getTypeName(), row.getFieldName(),
                    row.getParamName(), row.getArgumentPath());
                return new Defect(
                    row.getTypeName() + "." + row.getFieldName(),
                    row.getUseSite(), row.getParamName(), row.getArgumentPath(), false,
                    rejectionOf(Verdict.of(row.getVerdict()), entry, row.getNodeTypeRef(),
                        row.getTrailingSegmentName(),
                        keyColumnsOf(dsl, graphName, row.getNodeTypeRef()),
                        row.getColumnJavaType(), row.getParamJavaType(),
                        row.getLeafNamedType(), row.getTrailingSegments()),
                    location(row.getSourceName(), row.getSourceLine(), row.getSourceColumn()));
            });
    }

    /**
     * The projections that resolve but cannot be emitted, both reasons together. Keyed on the pair
     * rather than on the projection's own grain, so one row per bound parameter however many key
     * columns it projects: a distinct read over the finer grain would report a composite key twice
     * for one entry.
     *
     * <p>Two deferrals, and they are here rather than in SQL for one reason: each is a fact about
     * this codebase and not about the schema. A site whose emitter does not read a projection yet is
     * {@link #EMITTING_SITES}' business, and a list-shaped node id is an emitter that does not exist
     * for a request that is perfectly coherent, the author having named the list of one key column
     * across the decoded ids. A view cannot see either. Both arms shrink as emitters land, which is
     * what distinguishes them from the author defects the view states.
     */
    private static List<Defect> unemittableProjections(DSLContext dsl, String graphName) {
        var p = INTENT_RESOLVED_NODE_KEY_PROJECTION;
        var ap = INTENT_ARGMAPPING_PAIR;
        return dsl.selectDistinct(p.SITE, p.USE_SITE, p.TYPE_NAME, p.FIELD_NAME, p.POSITION,
                p.ARGUMENT_PATH, p.NODE_TYPE_NAME, p.LEAF_IS_LIST, ap.PARAM_NAME,
                ap.SOURCE_NAME, ap.SOURCE_LINE, ap.SOURCE_COLUMN)
            .from(p)
            .join(ap).on(ap.GRAPH_NAME.eq(p.GRAPH_NAME), ap.SITE.eq(p.SITE),
                ap.USE_SITE.eq(p.USE_SITE), ap.POSITION.eq(p.POSITION))
            .where(p.GRAPH_NAME.eq(graphName))
            .orderBy(p.TYPE_NAME, p.FIELD_NAME, p.USE_SITE, p.POSITION)
            .fetch()
            .stream()
            .filter(row -> Boolean.TRUE.equals(row.get(p.LEAF_IS_LIST))
                || !EMITTING_SITES.contains(Site.of(row.get(p.SITE))))
            .map(row -> {
                var site = Site.of(row.get(p.SITE));
                var entry = entry(site, row.get(p.USE_SITE), row.get(p.TYPE_NAME),
                    row.get(p.FIELD_NAME), row.get(ap.PARAM_NAME), row.get(p.ARGUMENT_PATH));
                // The list reason is reported ahead of the unwired-site one where both hold: it is
                // the shape the author can act on, an unwired site being nothing they control.
                String why = Boolean.TRUE.equals(row.get(p.LEAF_IS_LIST))
                    ? " opens a list of node ids, so it names the list of a key column of '"
                      + row.get(p.NODE_TYPE_NAME)
                      + "' across the decoded ids, which parameter binding does not emit yet"
                    : " resolves a key column of '" + row.get(p.NODE_TYPE_NAME)
                      + "', which no emitter reads at this site yet";
                return new Defect(
                    row.get(p.TYPE_NAME) + "." + row.get(p.FIELD_NAME),
                    row.get(p.USE_SITE), row.get(ap.PARAM_NAME), row.get(p.ARGUMENT_PATH), true,
                    Rejection.deferred(entry + why),
                    location(row.get(ap.SOURCE_NAME), row.get(ap.SOURCE_LINE),
                        row.get(ap.SOURCE_COLUMN)));
            })
            .toList();
    }

    /**
     * The message's leading clause: the directive, the entry as the author wrote it, and the use
     * site when that says more than the field coordinate the error already prefixes. A repeatable
     * directive's application ordinal and an argument-site condition's argument are what it adds;
     * on a plain field-grain site it adds nothing and is left off.
     */
    private static String entry(Site site, String useSite, String typeName, String fieldName,
                                String paramName, String argumentPath) {
        String at = useSite.equals(typeName + "." + fieldName) ? "" : " at " + useSite;
        return site.description() + " entry '" + paramName + ": " + argumentPath + "'" + at;
    }

    /**
     * Decodes one verdict into the {@link Rejection} arm the report carries. The unknown-column arm
     * is a typed {@link Rejection.AuthorError.UnknownName} so an editor offers the key list as a
     * fix rather than reading it out of prose; the other three are structural, there being no closed
     * name set to have missed. The type-mismatch arm reads the two types off the row rather than
     * resolving them here, so the message states exactly the operands the join compared and cannot
     * describe a different comparison than the one that rejected the pair.
     */
    private static Rejection rejectionOf(Verdict verdict, String entry, String nodeTypeRef,
                                         String trailingSegment, List<String> keyColumns,
                                         String columnJavaType, String paramJavaType,
                                         String leafNamedType, int trailingSegments) {
        return switch (verdict) {
            case UNDECLARED_NODE_ID -> Rejection.structural(entry + " opens "
                + article(leafNamedType) + " '" + leafNamedType + "' with '" + trailingSegment
                + "', which has nothing to open"
                + ("ID".equals(leafNamedType)
                    ? ": that ID declares no @nodeId, so there is no node identity to project a key"
                      + " column out of. Annotate it @nodeId(typeName: \"<NodeType>\") to open it"
                      + " into that node type's key columns"
                    : "; " + OPENABLE_KINDS));
            case TRAILING_SEGMENTS_BEYOND_ONE -> Rejection.structural(entry + " opens the "
                + nodeIdSpelling(nodeTypeRef) + " with '" + trailingSegment + "' and "
                + (trailingSegments - 1) + " more segment"
                + (trailingSegments - 1 == 1 ? "" : "s")
                + ", but a node id opens into exactly one key column, so nothing may follow it");
            case BARE_NODE_ID -> Rejection.structural(bareMessage(entry, nodeTypeRef, keyColumns));
            case MISSING_TYPE_NAME -> Rejection.structural(entry + " opens a @nodeId with '"
                + trailingSegment + "', but @nodeId must specify typeName: explicitly at an"
                + " argMapping position, there being no containing table here to name the NodeType"
                + " to decode against");
            case UNKNOWN_KEY_COLUMN -> Rejection.unknownNodeIdKeyColumn(
                entry + " names '" + trailingSegment + "', which is not a key column of '"
                + nodeTypeRef + "'"
                + (keyColumns.isEmpty()
                    ? "; '" + nodeTypeRef + "' resolves no key columns on any tier, so pin them"
                      + " with @node(keyColumns:) on that type"
                    : ""),
                trailingSegment, keyColumns);
            case KEY_COLUMN_TYPE_MISMATCH -> Rejection.structural(entry
                + (trailingSegment == null
                    ? " binds the " + nodeIdSpelling(nodeTypeRef) + ", whose key column '"
                      + soleColumn(keyColumns) + "'"
                    : " projects '" + trailingSegment + "' of '" + nodeTypeRef + "', which")
                + " jOOQ binds as " + simpleName(columnJavaType)
                + ", but the parameter it binds to takes " + simpleName(paramJavaType)
                + "; bind a parameter of the column's own type"
                + (keyColumns.size() > 1 ? ", or project a key column the parameter can take" : ""));
        };
    }

    /**
     * The two things a dot may open, as a message states them. One clause rather than two rules: the
     * separator has always meant "open the thing at this position", and what a thing opens into
     * follows from what it is.
     */
    private static final String OPENABLE_KINDS =
        "an input object opens into its fields, and an ID carrying @nodeId opens into the key"
        + " columns of the node type it names";

    /** {@code an} before a vowel, {@code a} otherwise; the type name is the author's own spelling. */
    private static String article(String typeName) {
        return typeName != null && !typeName.isEmpty()
            && "AEIOUaeiou".indexOf(typeName.charAt(0)) >= 0 ? "an" : "a";
    }

    /**
     * The whole bare-binding message, composed per way of having nothing to infer. Naming no key
     * column is not itself the defect: a node type keyed on one column has exactly one thing such a
     * binding could project, and that inference is a resolution the store states rather than a
     * defect this arm reports. So each clause here states the fact that leaves the inference with no
     * answer, and none of them claims the wire value would reach the database, which is true of all
     * three only because the build stops here.
     *
     * <p>Three clauses and not one prefix plus three remedies, because the fact and the remedy move
     * together: an unnamed type has no key list to count, a named type with no resolved key has a
     * remedy on the node type rather than on this entry, and a composite key is the only one of the
     * three where the author has columns in front of them to choose from. A one-column key never
     * arrives here, so no clause is written for it.
     */
    private static String bareMessage(String entry, String nodeTypeRef, List<String> keyColumns) {
        if (nodeTypeRef == null) {
            return entry + " binds a @nodeId that names no node type, so there is no key to decode"
                + " it against and nothing to infer a column from; specify typeName: on the @nodeId,"
                + " and open it with a key column if that type's key is more than one";
        }
        if (keyColumns.isEmpty()) {
            return entry + " binds the " + nodeIdSpelling(nodeTypeRef) + ", which resolves no key"
                + " columns on any tier, so nothing can be decoded out of it; pin them with"
                + " @node(keyColumns:) on that type";
        }
        return entry + " binds the " + nodeIdSpelling(nodeTypeRef) + ", whose key is "
            + keyColumns.size() + " columns, and one binding carries one value; open it with one of"
            + " them: " + String.join(", ", keyColumns);
    }

    /**
     * The one key column a node type of arity 1 has, which an inferred projection names because the
     * author named nothing. Fetched as the key list rather than as a distinct fact, this being the
     * same list the composite message enumerates; a caller reaching here with any other size is
     * reading a row the inference should have claimed, so the shape is asserted rather than
     * defended.
     */
    private static String soleColumn(List<String> keyColumns) {
        if (keyColumns.size() != 1) {
            throw new IllegalStateException(
                "Graphitron generator bug (key projection): a type mismatch names no trailing"
                + " segment, so the column was inferred from a one-column key, but the node type"
                + " resolves " + keyColumns.size() + " key columns " + keyColumns
                + "; the inferring arm and the key-column relation have drifted");
        }
        return keyColumns.get(0);
    }

    /** The store's position columns as a graphql-java location; {@code null} when unpositioned. */
    private static SourceLocation location(String sourceName, Integer line, Integer column) {
        if (line == null || column == null) {
            return null;
        }
        return new SourceLocation(line, column, sourceName);
    }
}
