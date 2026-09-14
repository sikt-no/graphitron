package no.sikt.graphitron.rewrite.generators;

import no.sikt.graphitron.javapoet.ClassName;
import no.sikt.graphitron.render.CatalogRefs;
import no.sikt.graphitron.rewrite.model.CallSiteExtraction;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The single home for every private-static helper-method name emitted on one {@code <Type>Fetchers}
 * class. Two prefix namespaces write into that class's method namespace, keyed on two different
 * things:
 *
 * <ul>
 *   <li>the <b>{@code create*}</b> namespace, whose stem set spans the <em>union</em> of the
 *       jOOQ-record carrier classes and the collected bean (POJO / {@code @record}) classes. Both
 *       families emit {@code create<stem>} / {@code create<stem>List}, so their stems must be
 *       unique across the union, not merely within each family. The jOOQ-record arm's shape
 *       contention (one record class reached by several binding shapes, disambiguated with ordinal
 *       suffixes) is layered on top by {@link JooqRecordHelperNames}, whose per-class <em>base
 *       stem</em> comes from this resolver's {@code create*} stem map. Cross-class
 *       disambiguation ("which class") and within-class shape contention ("which binding shape")
 *       are orthogonal and compose as {@code create<stem><ordinal>}.</li>
 *   <li>the <b>{@code decode*}</b> namespace, which is <em>not</em> keyed on a Java class at all:
 *       both its arms are GraphQL type names. The single-type arm names one {@code @node} type
 *       ({@code decode<TypeName>Record} / {@code decode<TypeName>RecordList}; scalar and list
 *       variants share one stem), because a record decode is a function of the node type and two
 *       node types can sit over one table. The container arm names a polymorphic container a
 *       {@code @nodeId(typeName:)} points at a slot, whose helper decodes into whichever member's
 *       record the wire id belongs to. One schema cannot declare a node type and a container under
 *       one name, so the two arms cannot collide by construction; {@link #of} checks the emitted
 *       names anyway, for the day a producer feeds the namespace something that is not a schema
 *       type name.</li>
 * </ul>
 *
 * <h3>Stem rule</h3>
 *
 * <p>A class whose simple name is unique within its namespace keeps {@link ClassName#simpleName()}
 * as its stem (the overwhelmingly common single-schema case). A class whose simple name collides
 * gets a disambiguator <em>prefixed</em> to the simple name, derived from the class's own package
 * (a stable per-class fact, independent of which other classes happen to collide with it); token
 * choice and escalation live on {@link #disambiguatorTokens} and {@link #disambiguate}. Uniqueness
 * is enforced over the <em>emitted method names</em> (singular and plural forms both), catching the
 * {@code create<A>List}-versus-bean-named-{@code AList} overlap.
 *
 * <p>The disambiguator is derived from the already-resolved javapoet {@link ClassName#packageName()}
 * only: it never reaches back into raw jOOQ ({@code Table.getSchema()} stays behind the
 * {@code JooqCatalog} parse boundary).
 *
 * <p>The rule governs the {@code create*} namespace alone. The {@code decode*} names are the
 * schema's own type names, unique within a schema, so there is nothing there to disambiguate; what
 * that namespace keeps instead is the claimed-name check in {@link #of}.
 *
 * <h3>The populated / default split</h3>
 *
 * <p>{@link #of} builds a <em>populated</em> resolver from a class's collected carriers, beans, and
 * decoders. {@link #bare} returns the <em>default</em> resolver of schema-free / unit / out-of-band
 * contexts, which by construction carry at most one class per simple name and so answer the bare
 * {@code simpleName()}-based names unconditionally.
 */
final class FetchersHelperNames {

    private final boolean populated;
    /** {@code create*}-namespace class → base stem (before jOOQ shape-contention ordinals). */
    private final Map<ClassName, String> createStems;
    /** The node types whose {@code decode<TypeName>Record} helper this class hosts. */
    private final Set<String> decodeTypeNames;
    /** The polymorphic containers whose {@code decode<Container>Record} helper this class hosts. */
    private final Set<String> decodeContainerNames;
    /** The jOOQ-record shape-contention arm, built with base stems drawn from {@link #createStems}. */
    private final JooqRecordHelperNames jooqRecord;

    private FetchersHelperNames(boolean populated, Map<ClassName, String> createStems,
            Set<String> decodeTypeNames, Set<String> decodeContainerNames,
            JooqRecordHelperNames jooqRecord) {
        this.populated = populated;
        this.createStems = createStems;
        this.decodeTypeNames = decodeTypeNames;
        this.decodeContainerNames = decodeContainerNames;
        this.jooqRecord = jooqRecord;
    }

    /**
     * The default (never-populated) resolver: answers {@code simpleName()}-based names for any class
     * and carries a bare jOOQ arm. Used by schema-free / unit / out-of-band emission contexts, which
     * by construction carry at most one class per simple name.
     */
    static FetchersHelperNames bare() {
        return new FetchersHelperNames(false, Map.of(), Set.of(), Set.of(),
            JooqRecordHelperNames.bare());
    }

    /**
     * Build the populated resolver for one {@code <Type>Fetchers} class from every jOOQ-record
     * carrier, every collected bean class, and every node type whose {@code @nodeId} record decode
     * this class hosts. The {@code create*} stem set is computed over the union of the jOOQ-record
     * carrier classes and the bean classes; the jOOQ arm is then built with the {@code create*} base
     * stems so its shape ordinals compose over the cross-class stem.
     */
    static FetchersHelperNames of(Collection<CallSiteExtraction.JooqRecord> jooqCarriers,
            Collection<ClassName> beanClasses, Collection<String> decodeTypeNames) {
        return of(jooqCarriers, beanClasses, decodeTypeNames, Map.of());
    }

    /**
     * The four-input form, adding the polymorphic containers a {@code @nodeId(typeName:)} names at a
     * slot on this class, each mapped to the member type names its helper dispatches over. The
     * members are taken rather than inferred because they are a third claimant on the same
     * namespace ({@link #decodeContainerMember}) and the check below is the only place all three
     * meet.
     */
    static FetchersHelperNames of(Collection<CallSiteExtraction.JooqRecord> jooqCarriers,
            Collection<ClassName> beanClasses, Collection<String> decodeTypeNames,
            Map<String, ? extends Collection<String>> decodeContainers) {
        var createClasses = new LinkedHashSet<ClassName>();
        for (var jr : jooqCarriers) {
            createClasses.add(CatalogRefs.recordClass(jr.table()));
        }
        createClasses.addAll(beanClasses);

        var createStems = disambiguate("create", createClasses);
        var types = new LinkedHashSet<>(decodeTypeNames);
        checkDecodeNamespace(types, decodeContainers);
        var jooqRecord = JooqRecordHelperNames.of(jooqCarriers, createStems);
        return new FetchersHelperNames(true, createStems, types,
            new LinkedHashSet<>(decodeContainers.keySet()), jooqRecord);
    }

    /**
     * Refuses a duplicate claim on the {@code decode*} namespace. Both arms are GraphQL type names
     * and one schema declares each name once, so this cannot fire on schema input; it fires the day
     * a producer feeds the namespace a name that is not a schema type name, which is the fault the
     * retired per-class stem resolution used to paper over with an ordinal. Same refusal shape as
     * the routing-hole checks below, and for the same reason: a silently shared name is a decode
     * body that answers for a type it was not built for.
     */
    private static void checkDecodeNamespace(Set<String> typeNames,
            Map<String, ? extends Collection<String>> containers) {
        var claimed = new LinkedHashSet<String>();
        for (String typeName : typeNames) {
            claim(claimed, decodeRecordName(typeName));
            claim(claimed, decodeRecordName(typeName) + "List");
        }
        for (var container : containers.entrySet()) {
            String stem = decodeRecordName(container.getKey());
            claim(claimed, stem);
            claim(claimed, stem + "List");
            for (String member : container.getValue()) {
                claim(claimed, stem + member);
            }
        }
    }

    private static void claim(Set<String> claimed, String name) {
        if (!claimed.add(name)) {
            throw new IllegalStateException(
                "Two decode* helpers on one <Type>Fetchers class claim the name '" + name + "'."
                + " Every decode* name is a GraphQL type name, which a schema declares once, so a"
                + " duplicate claim means a naming site fed the resolver something that is not one.");
        }
    }

    /** The jOOQ-record shape-aware {@code create<Record>} resolver for this class. */
    JooqRecordHelperNames jooqRecord() {
        return jooqRecord;
    }

    /** {@code create<Bean>} singular helper name for a bean / {@code @record} POJO class. */
    String createSingular(ClassName beanClass) {
        return "create" + createStem(beanClass);
    }

    /** {@code create<Bean>List} plural helper name. */
    String createPlural(ClassName beanClass) {
        return createSingular(beanClass) + "List";
    }

    /**
     * {@code decode<TypeName>Record} scalar node-ID decode helper name, named from the node type the
     * decode is a function of rather than from the record class of its table: two node types can
     * back one table, and each checks the wire id against its own typeId.
     */
    String decodeSingular(String typeName) {
        if (populated && !decodeTypeNames.contains(typeName)) {
            throw uncollectedDecode("node type", typeName);
        }
        return decodeRecordName(typeName);
    }

    /** {@code decode<TypeName>RecordList} list node-ID decode helper name. */
    String decodeList(String typeName) {
        return decodeSingular(typeName) + "List";
    }

    /**
     * {@code decode<Container>Record} scalar helper name for a polymorphic container, the helper that
     * reads the wire id's type prefix and decodes into whichever member's record it names.
     */
    String decodeContainerSingular(String containerName) {
        if (populated && !decodeContainerNames.contains(containerName)) {
            throw uncollectedDecode("polymorphic container", containerName);
        }
        return decodeRecordName(containerName);
    }

    /** {@code decode<Container>RecordList} list form of the container helper. */
    String decodeContainerList(String containerName) {
        return decodeContainerSingular(containerName) + "List";
    }

    /**
     * The null-returning per-member helper one container arm calls, named inside the container's own
     * claimed stem rather than in the single-type namespace. Deliberately not
     * {@link #decodeSingular} of the member's own type name: that name is the <em>throwing</em>
     * single-type helper's, and the same class can host both for the same node type, so sharing the
     * name would make one member's arm raise instead of standing aside.
     */
    String decodeContainerMember(String containerName, String memberTypeName) {
        return decodeContainerSingular(containerName) + memberTypeName;
    }

    private String createStem(ClassName c) {
        if (!populated) {
            return c.simpleName();
        }
        return required(createStems, c, "create");
    }

    /** The one spelling of a {@code decode*} stem: both arms are GraphQL type names. */
    private static String decodeRecordName(String graphqlTypeName) {
        return "decode" + graphqlTypeName + "Record";
    }

    /**
     * The same routing hole {@link #required} refuses for the class-keyed namespace, for the same
     * reason: a silent fallback would name a helper nothing emitted.
     */
    private static IllegalStateException uncollectedDecode(String what, String name) {
        return new IllegalStateException(
            "FetchersHelperNames was asked to name a decode* helper for a " + what
            + " it never collected: " + name + ". Every naming site must route through the resolver"
            + " built from this <Type>Fetchers class's carriers, beans, decoders and containers.");
    }

    private static String required(Map<ClassName, String> stems, ClassName c, String namespace) {
        String stem = stems.get(c);
        if (stem == null) {
            // A populated resolver asked to name a class it never collected is a routing hole: a
            // silent simpleName fallback would re-bury the collision this resolver exists to prevent
            // (a call site naming a helper that was never emitted, or the wrong class's helper).
            throw new IllegalStateException(
                "FetchersHelperNames was asked to name a " + namespace + "* helper for a class it "
                + "never collected: " + c + ". Every naming site must route through the resolver "
                + "built from this <Type>Fetchers class's carriers, beans, and decoders.");
        }
        return stem;
    }

    // -----------------------------------------------------------------------
    // Stem disambiguation
    // -----------------------------------------------------------------------

    /**
     * Resolve a collision-free base stem for every class in one prefix namespace. Starts each class
     * at its bare {@link ClassName#simpleName()}; while any two classes' emitted method names (the
     * {@code prefix + stem} singular and {@code prefix + stem + "List"} plural forms) collide, bumps
     * the colliding classes one disambiguation level deeper (one more package segment prefixed,
     * right-to-left). When the package segments are exhausted for every still-colliding class, a
     * 1-based ordinal ordered by full class name guarantees termination.
     */
    private static Map<ClassName, String> disambiguate(String prefix, Set<ClassName> classes) {
        var order = new ArrayList<>(classes);
        var level = new LinkedHashMap<ClassName, Integer>();
        var tokens = new LinkedHashMap<ClassName, List<String>>();
        for (var c : order) {
            level.put(c, 0);
            tokens.put(c, disambiguatorTokens(c));
        }

        while (true) {
            var stems = new LinkedHashMap<ClassName, String>();
            for (var c : order) {
                stems.put(c, stemAtLevel(c, level.get(c), tokens.get(c)));
            }
            var colliding = collidingClasses(prefix, order, stems);
            if (colliding.isEmpty()) {
                return stems;
            }
            boolean bumped = false;
            for (var c : colliding) {
                if (level.get(c) < tokens.get(c).size()) {
                    level.put(c, level.get(c) + 1);
                    bumped = true;
                }
            }
            if (!bumped) {
                // Every still-colliding class has exhausted its package segments: fall back to a
                // 1-based ordinal (ordered by full class name) appended to its deepest stem.
                var ordered = new ArrayList<>(colliding);
                ordered.sort(Comparator.comparing(ClassName::toString));
                for (int i = 0; i < ordered.size(); i++) {
                    var c = ordered.get(i);
                    stems.put(c, stems.get(c) + (i + 1));
                }
                return stems;
            }
        }
    }

    /** Classes whose singular or plural emitted method name is claimed by more than one class. */
    private static Set<ClassName> collidingClasses(String prefix, List<ClassName> order,
            Map<ClassName, String> stems) {
        var owners = new LinkedHashMap<String, List<ClassName>>();
        for (var c : order) {
            String stem = stems.get(c);
            owners.computeIfAbsent(prefix + stem, k -> new ArrayList<>()).add(c);
            owners.computeIfAbsent(prefix + stem + "List", k -> new ArrayList<>()).add(c);
        }
        var colliding = new LinkedHashSet<ClassName>();
        for (var owned : owners.values()) {
            if (owned.size() > 1) {
                colliding.addAll(owned);
            }
        }
        return colliding;
    }

    /**
     * The stem for {@code c} at a disambiguation level: level 0 is the bare simple name; level
     * {@code k} prefixes the {@code k} deepest disambiguator tokens, in package order (leftmost
     * package segment leftmost in the stem).
     */
    private static String stemAtLevel(ClassName c, int level, List<String> tokens) {
        if (level == 0) {
            return c.simpleName();
        }
        var sb = new StringBuilder();
        for (int i = level - 1; i >= 0; i--) {
            sb.append(tokens.get(i));
        }
        return sb.append(c.simpleName()).toString();
    }

    /**
     * The ordered pascal-cased package tokens available to disambiguate {@code c}, primary token
     * first. For a jOOQ-layout package ({@code …<schema>.tables.records}) the primary is the schema
     * segment (the one immediately before {@code tables.records}); for any other package it is the
     * last segment. Remaining tokens extend right-to-left toward the package root.
     */
    private static List<String> disambiguatorTokens(ClassName c) {
        String pkg = c.packageName();
        if (pkg.isEmpty()) {
            return List.of();
        }
        String[] segs = pkg.split("\\.");
        int primaryIdx = pkg.endsWith(".tables.records")
            ? segs.length - 3   // the schema segment, before "tables.records"
            : segs.length - 1;  // the last package segment
        var out = new ArrayList<String>(primaryIdx + 1);
        for (int i = primaryIdx; i >= 0; i--) {
            out.add(pascalCase(segs[i]));
        }
        return out;
    }

    /** Pascal-case a snake_case package segment: {@code multischema_a} → {@code MultischemaA}. */
    private static String pascalCase(String segment) {
        if (segment.isEmpty()) {
            return segment;
        }
        String camel = GeneratorUtils.toCamelCase(segment);
        return Character.toUpperCase(camel.charAt(0)) + camel.substring(1);
    }
}
