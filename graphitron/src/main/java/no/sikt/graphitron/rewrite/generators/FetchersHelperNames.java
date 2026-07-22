package no.sikt.graphitron.rewrite.generators;

import no.sikt.graphitron.javapoet.ClassName;
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
 * class. Two prefix namespaces write into that class's method namespace, each keyed on a Java class
 * whose <em>simple</em> name can collide across schema packages (jOOQ generates one record class per
 * {@code <schema>.<table>}, so two schemas with a same-named table produce two classes sharing a
 * simple name but not a package):
 *
 * <ul>
 *   <li>the <b>{@code create*}</b> namespace, whose stem set spans the <em>union</em> of the
 *       jOOQ-record carrier classes and the collected bean (POJO / {@code @record}) classes. Both
 *       families emit {@code create<stem>} / {@code create<stem>List}, so their stems must be
 *       unique across the union, not merely within each family. The jOOQ-record arm's shape
 *       contention (one record class reached by several binding shapes, disambiguated with ordinal
 *       suffixes) is layered on top by {@link JooqRecordHelperNames}, whose per-class <em>base
 *       stem</em> now comes from this resolver's {@code create*} stem map. Cross-class
 *       disambiguation ("which class") and within-class shape contention ("which binding shape")
 *       are orthogonal and compose as {@code create<stem><ordinal>}.</li>
 *   <li>the <b>{@code decode*}</b> namespace, whose stem set spans the {@code @nodeId} record-decode
 *       target classes ({@code decode<stem>} / {@code decode<stem>List}; scalar and list variants
 *       share one stem).</li>
 * </ul>
 *
 * <h3>Stem rule</h3>
 *
 * <p>A class whose simple name is unique within its namespace keeps {@link ClassName#simpleName()}
 * as its stem, so the overwhelmingly common single-schema case is byte-for-byte unchanged. A class
 * whose simple name collides gets a disambiguator <em>prefixed</em> to the simple name, derived from
 * the class's own package (a stable per-class fact, independent of which other classes happen to
 * collide with it): for a jOOQ-layout package ({@code …<schema>.tables.records}) the schema segment,
 * for any other package the last segment, each pascal-cased. Uniqueness is enforced over the
 * <em>emitted method names</em> (singular and plural forms both), catching the
 * {@code create<A>List}-versus-bean-named-{@code AList} overlap. If the disambiguator still yields
 * duplicates (distinct package segments that pascal-case to the same token, or degenerate layouts),
 * the rule extends deterministically with further package segments right-to-left; if that exhausts,
 * a 1-based ordinal ordered by full class name guarantees termination.
 *
 * <p>The disambiguator is derived from the already-resolved javapoet {@link ClassName#packageName()}
 * only: it never reaches back into raw jOOQ ({@code Table.getSchema()} stays behind the
 * {@code JooqCatalog} parse boundary).
 *
 * <h3>The populated / default split</h3>
 *
 * <p>{@link #of} builds a <em>populated</em> resolver from a class's collected carriers, beans, and
 * decoders. {@link #bare} returns the <em>default</em> resolver of schema-free / unit / out-of-band
 * contexts, which by construction carry at most one class per simple name and so answer the bare
 * {@code simpleName()}-based names unconditionally, preserving today's behaviour.
 */
final class FetchersHelperNames {

    private final boolean populated;
    /** {@code create*}-namespace class → base stem (before jOOQ shape-contention ordinals). */
    private final Map<ClassName, String> createStems;
    /** {@code decode*}-namespace class → base stem. */
    private final Map<ClassName, String> decodeStems;
    /** The jOOQ-record shape-contention arm, built with base stems drawn from {@link #createStems}. */
    private final JooqRecordHelperNames jooqRecord;

    private FetchersHelperNames(boolean populated, Map<ClassName, String> createStems,
            Map<ClassName, String> decodeStems, JooqRecordHelperNames jooqRecord) {
        this.populated = populated;
        this.createStems = createStems;
        this.decodeStems = decodeStems;
        this.jooqRecord = jooqRecord;
    }

    /**
     * The default (never-populated) resolver: answers {@code simpleName()}-based names for any class
     * and carries a bare jOOQ arm. Used by schema-free / unit / out-of-band emission contexts, which
     * by construction carry at most one class per simple name.
     */
    static FetchersHelperNames bare() {
        return new FetchersHelperNames(false, Map.of(), Map.of(), JooqRecordHelperNames.bare());
    }

    /**
     * Build the populated resolver for one {@code <Type>Fetchers} class from every jOOQ-record
     * carrier, every collected bean class, and every {@code @nodeId} record-decode target class on
     * it. The {@code create*} stem set is computed over the union of the jOOQ-record carrier classes
     * and the bean classes; the {@code decode*} stem set over the decode target classes. The jOOQ arm
     * is then built with the {@code create*} base stems so its shape ordinals compose over the
     * cross-class stem.
     */
    static FetchersHelperNames of(Collection<CallSiteExtraction.JooqRecord> jooqCarriers,
            Collection<ClassName> beanClasses, Collection<ClassName> decodeRecordClasses) {
        var createClasses = new LinkedHashSet<ClassName>();
        for (var jr : jooqCarriers) {
            createClasses.add(jr.table().recordClass());
        }
        createClasses.addAll(beanClasses);

        var createStems = disambiguate("create", createClasses);
        var decodeStems = disambiguate("decode", new LinkedHashSet<>(decodeRecordClasses));
        var jooqRecord = JooqRecordHelperNames.of(jooqCarriers, createStems);
        return new FetchersHelperNames(true, createStems, decodeStems, jooqRecord);
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

    /** {@code decode<Record>} scalar node-ID decode helper name. */
    String decodeSingular(ClassName recordClass) {
        return "decode" + decodeStem(recordClass);
    }

    /** {@code decode<Record>List} list node-ID decode helper name. */
    String decodeList(ClassName recordClass) {
        return decodeSingular(recordClass) + "List";
    }

    private String createStem(ClassName c) {
        if (!populated) {
            return c.simpleName();
        }
        return required(createStems, c, "create");
    }

    private String decodeStem(ClassName c) {
        if (!populated) {
            return c.simpleName();
        }
        return required(decodeStems, c, "decode");
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
