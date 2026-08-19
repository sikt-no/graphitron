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
import static no.sikt.graphitron.model.Tables.INTENT_RESOLVED_NODE_KEY_COLUMN;
import static no.sikt.graphitron.model.Tables.INTENT_RESOLVED_NODE_KEY_PROJECTION;

/**
 * The {@code @nodeId} key-projection rules for {@code argMapping}, projected from the store: an
 * {@code argMapping} path that binds a node id either projects one of that node type's key columns
 * or is one of the defects below, and this class derives the located {@link ValidationError} values
 * for both halves. The reduction lives in the SQL, in
 * {@code intent_argmapping_projection_defect} and the projection relation beside it; what remains
 * here is the decode of the view's closed {@code verdict} vocabulary into {@link Rejection} arms and
 * the prose those arms carry.
 *
 * <p>Three of the four arms are the author's to fix, and they close the silence this family had:
 * binding a {@code @nodeId} without naming a key column used to hand a routine parameter or a
 * service method the base64 wire id verbatim, and nothing in the build said a word. Which arm fires
 * is the view's decision, taken on the trailing-segment count alone, so nothing here re-tests a
 * predicate the query already settled. What a dot opens is a node id, so an {@code ID} declaring no
 * {@code @nodeId} has nothing to open and is the walk's rejection rather than a verdict here: the
 * grammar admits only what it can confirm, which is what keeps that rule in one place.
 *
 * <p>The fourth arm is the generator's rather than the author's, which is why it is derived here
 * and not in SQL: a projection that resolves at a site whose emitter does not read it yet is a
 * {@link Rejection.Deferred}, and whether an emitter exists is a fact about this codebase and not
 * about the schema. {@link #EMITTING_SITES} is that fact, held beside the switch that names the
 * eight sites so a value can neither be misspelled nor forgotten, and the arm shrinks as sites are
 * wired rather than being deleted. A projection at a site still outside that set fails the build
 * saying so, which is the honest state: emitting nothing, or emitting the raw base64, are the two
 * outcomes it exists to prevent.
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
     * The {@code site} values whose emitters read a resolved key projection. {@code ROUTINE} is
     * wired: a routine IN parameter bound to a projected path reads its column off a decoded record
     * through {@link no.sikt.graphitron.render.ProjectedKeyReads}. The service and output-field
     * condition sites join it when their emitters land, leaving the input-field condition and the
     * three path-step sites, which resolve no leaf at all and can therefore only ever defer.
     *
     * <p>The set is keyed on the site and so says nothing about whether every <em>emitter</em> at a
     * wired site reads a projection. That second question is the plan's, asked as row presence in
     * {@code EmitPlan}: a projection at a coordinate no wired emitter owns fails the build there
     * rather than being emitted as an ordinary nested read. The two gates are complementary, and
     * neither subsumes the other, because this one runs before any plan exists and that one cannot
     * see the directive a pair was spelled on.
     */
    private static final Set<Site> EMITTING_SITES = EnumSet.of(Site.ROUTINE);

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
        /** A declared decode with no key column named after it: the silently-wrong binding. */
        BARE_NODE_ID,
        /** A projection asked for against a {@code @nodeId} that names no node type. */
        MISSING_TYPE_NAME,
        /** A trailing segment naming no key column the node type resolved. */
        UNKNOWN_KEY_COLUMN;

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
     * three author defects from the detection view, then the unwired-site deferrals from the
     * projection relation. Empty for a graph whose {@code argMapping} paths all bind ordinary
     * values, and for one whose projections all resolve at sites that emit them.
     */
    public static Detection detect(DSLContext dsl, String graphName) {
        var defects = new ArrayList<Defect>(authorDefects(dsl, graphName));
        defects.addAll(unwiredSites(dsl, graphName));
        return new Detection(defects);
    }

    /** The view's three arms, decoded. */
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
                        keyColumnsOf(dsl, graphName, row.getNodeTypeRef())),
                    location(row.getSourceName(), row.getSourceLine(), row.getSourceColumn()));
            });
    }

    /**
     * The projections that resolve at a site no emitter reads. Keyed on {@code site} rather than on
     * the pair, so one row per pair however many key columns it projects: a distinct read over the
     * projection relation's own grain would report a composite key twice for one entry. Empty once
     * every site that resolves a leaf is wired, which is what makes this a shrinking arm rather than
     * a permanent one.
     */
    private static List<Defect> unwiredSites(DSLContext dsl, String graphName) {
        var p = INTENT_RESOLVED_NODE_KEY_PROJECTION;
        var ap = INTENT_ARGMAPPING_PAIR;
        return dsl.selectDistinct(p.SITE, p.USE_SITE, p.TYPE_NAME, p.FIELD_NAME, p.POSITION,
                p.ARGUMENT_PATH, p.NODE_TYPE_NAME, ap.PARAM_NAME,
                ap.SOURCE_NAME, ap.SOURCE_LINE, ap.SOURCE_COLUMN)
            .from(p)
            .join(ap).on(ap.GRAPH_NAME.eq(p.GRAPH_NAME), ap.SITE.eq(p.SITE),
                ap.USE_SITE.eq(p.USE_SITE), ap.POSITION.eq(p.POSITION))
            .where(p.GRAPH_NAME.eq(graphName))
            .orderBy(p.TYPE_NAME, p.FIELD_NAME, p.USE_SITE, p.POSITION)
            .fetch()
            .stream()
            .filter(row -> !EMITTING_SITES.contains(Site.of(row.get(p.SITE))))
            .map(row -> {
                var site = Site.of(row.get(p.SITE));
                var entry = entry(site, row.get(p.USE_SITE), row.get(p.TYPE_NAME),
                    row.get(p.FIELD_NAME), row.get(ap.PARAM_NAME), row.get(p.ARGUMENT_PATH));
                return new Defect(
                    row.get(p.TYPE_NAME) + "." + row.get(p.FIELD_NAME),
                    row.get(p.USE_SITE), row.get(ap.PARAM_NAME), row.get(p.ARGUMENT_PATH), true,
                    Rejection.deferred(entry + " resolves a key column of '"
                        + row.get(p.NODE_TYPE_NAME) + "', which no emitter reads at this site yet"),
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
     * The node type's resolved key columns, in key order: the candidate list a message offers, read
     * as rows off the relation that resolved them rather than as a render the view joined and this
     * had to split apart. Empty where the type is unnamed and where no tier answered for it, which
     * are two different facts the caller tells apart by {@code nodeTypeRef}.
     */
    private static List<String> keyColumnsOf(DSLContext dsl, String graphName, String nodeTypeRef) {
        if (nodeTypeRef == null) {
            return List.of();
        }
        var k = INTENT_RESOLVED_NODE_KEY_COLUMN;
        return dsl.select(k.COLUMN_NAME)
            .from(k)
            .where(k.GRAPH_NAME.eq(graphName), k.TYPE_NAME.eq(nodeTypeRef))
            .orderBy(k.POSITION)
            .fetch(r -> r.value1());
    }

    /**
     * Decodes one verdict into the {@link Rejection} arm the report carries. The unknown-column arm
     * is a typed {@link Rejection.AuthorError.UnknownName} so an editor offers the key list as a
     * fix rather than reading it out of prose; the other two are structural, there being no closed
     * name set to have missed.
     */
    private static Rejection rejectionOf(Verdict verdict, String entry, String nodeTypeRef,
                                         String trailingSegment, List<String> keyColumns) {
        return switch (verdict) {
            case BARE_NODE_ID -> Rejection.structural(entry + " binds a "
                + nodeIdSpelling(nodeTypeRef)
                + " and names no key column, so the encoded node id would reach the database"
                + " verbatim; " + bareRemedy(nodeTypeRef, keyColumns));
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
        };
    }

    /** {@code @nodeId(typeName: "X")} where the author named a type, {@code @nodeId} where not. */
    private static String nodeIdSpelling(String nodeTypeRef) {
        return nodeTypeRef == null ? "@nodeId" : "@nodeId(typeName: \"" + nodeTypeRef + "\")";
    }

    /**
     * What to write instead, which differs by how far the author got: naming no type leaves two
     * things to add, naming a type with a resolved key leaves the column, and naming one with no
     * resolved key puts the remedy on the node type rather than on this entry.
     */
    private static String bareRemedy(String nodeTypeRef, List<String> keyColumns) {
        if (nodeTypeRef == null) {
            return "specify typeName: on the @nodeId and open it with one of that type's key"
                + " columns";
        }
        if (keyColumns.isEmpty()) {
            return "'" + nodeTypeRef + "' resolves no key columns on any tier, so pin them with"
                + " @node(keyColumns:) on that type";
        }
        return "open it with one of the key columns of '" + nodeTypeRef + "': "
            + String.join(", ", keyColumns);
    }

    /** The store's position columns as a graphql-java location; {@code null} when unpositioned. */
    private static SourceLocation location(String sourceName, Integer line, Integer column) {
        if (line == null || column == null) {
            return null;
        }
        return new SourceLocation(line, column, sourceName);
    }
}
