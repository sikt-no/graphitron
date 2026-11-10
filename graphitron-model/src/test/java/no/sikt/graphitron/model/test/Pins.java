package no.sikt.graphitron.model.test;

import no.sikt.graphitron.model.test.CorpusExpectations.Block;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * What a document's claims pin: the behaviours the model says an example means, read from the store
 * rows each claim matched. A document pins a behaviour when its claims assert rows of it; containing
 * one is not pinning it, since every scenario holds a table type's claim and few say so.
 *
 * <p>The behaviours fall in three families: the claims elements make, the defects a reading finds,
 * and the occurrences an element has along a chain, each read from an anchor relation, the defects
 * from two, since a refusal the toolchain raises answers an author as a defect does. A claimed relation
 * belongs to a family when {@code meta_relation} declares it at the anchor's grain, so a new relation
 * stating more about the same thing joins its family by being declared. Each family states its behaviour in
 * structural kinds, never names: two documents pinning a {@code SERVICE} claim on a field pin the
 * same behaviour whatever the field is called.
 */
final class Pins {

    private Pins() {}

    /** A family of behaviours, which one or more anchors pin. */
    enum Family { CLAIM, DEFECT, OCCURRENCE }

    /**
     * A relation a family's behaviours are read from: its family, the relation, its key, the
     * projections that pin as the anchor itself rather than as a facet of it, and its shape.
     */
    enum Anchor {
        /** The claims elements make: the claim's kind and the kind of element making it. */
        CLAIM(Family.CLAIM, "graphitron_claim", List.of("graph_name", "coordinate", "kind", "claimer"),
            List.of("graphitron_producer_claim"), """
            SELECT DISTINCT CONCAT_WS('|', k.kind, COALESCE(e.element_kind, '-'))
              FROM graphitron_claim k
              LEFT JOIN graphql_element e ON e.graph_name = k.graph_name AND e.coordinate = k.coordinate
             WHERE (k.graph_name, k.coordinate, k.kind, k.claimer) IN ({0})
            """),
        /**
         * The defects a reading finds: the code, the kind of AST entry it sits at, and the directive
         * applied there or around it, found in the hops {@code graphitron_entry_defect_site} takes.
         */
        DEFECT(Family.DEFECT, "graphitron_entry_defect",
            List.of("graph_name", "source_name", "source_line", "source_column", "code"),
            List.of("graphitron_entry_defect_site"), """
            SELECT DISTINCT CONCAT_WS('|', k.code, n.entry_kind, COALESCE(a.directive_name, '-'))
              FROM graphitron_entry_defect k
              JOIN graphql_ast_entry n
                ON n.graph_name = k.graph_name AND n.source_name = k.source_name
               AND n.source_line = k.source_line AND n.source_column = k.source_column
              LEFT JOIN graphql_ast_value_entry v
                ON v.graph_name = k.graph_name AND v.source_name = k.source_name
               AND v.source_line = k.source_line AND v.source_column = k.source_column
              LEFT JOIN graphql_ast_entry h
                ON h.graph_name = v.graph_name AND h.source_name = v.source_name
               AND h.source_line = v.holder_line AND h.source_column = v.holder_column
              LEFT JOIN graphql_directive_application a
                ON a.graph_name = n.graph_name AND a.source_name = n.source_name
               AND a.source_line = CASE
                     WHEN h.entry_kind = 'APPLIED_ARGUMENT' THEN h.parent_line
                     WHEN n.entry_kind = 'APPLIED_ARGUMENT' THEN n.parent_line
                     WHEN n.entry_kind LIKE '%DIRECTIVE' THEN n.source_line END
               AND a.source_column = CASE
                     WHEN h.entry_kind = 'APPLIED_ARGUMENT' THEN h.parent_column
                     WHEN n.entry_kind = 'APPLIED_ARGUMENT' THEN n.parent_column
                     WHEN n.entry_kind LIKE '%DIRECTIVE' THEN n.source_column END
             WHERE (k.graph_name, k.source_name, k.source_line, k.source_column, k.code) IN ({0})
            """),
        /**
         * The refusals the toolchain raises making a schema of the documents: the stage and the
         * stage's own name for the kind. Not entry defects, having no declared code and not always a
         * written position, but the same answer to an author: this schema is refused, and why.
         */
        PROBLEM(Family.DEFECT, "graphql_schema_problem", List.of("graph_name", "stage", "ordinal"), List.of(), """
            SELECT DISTINCT CONCAT_WS('|', k.stage, k.error_class)
              FROM graphql_schema_problem k
             WHERE (k.graph_name, k.stage, k.ordinal) IN ({0})
            """),
        /** The occurrences along a chain: the occurrence's kind, its producer's, root or nested. */
        OCCURRENCE(Family.OCCURRENCE, "graphitron_occurrence", List.of("graph_name", "coordinate"), List.of(), """
            SELECT DISTINCT CONCAT_WS('|', k.kind, COALESCE(p.kind, '-'),
                                      CASE WHEN k.depth = 0 THEN 'ROOT' ELSE 'NESTED' END)
              FROM graphitron_occurrence k
              LEFT JOIN graphitron_occurrence_producer p
                ON p.graph_name = k.graph_name AND p.coordinate = k.coordinate
             WHERE (k.graph_name, k.coordinate) IN ({0})
            """);

        final Family family;
        final String anchor;
        final List<String> key;
        final List<String> projections;
        final String shape;

        Anchor(Family family, String anchor, List<String> key, List<String> projections, String shape) {
            this.family = family;
            this.anchor = anchor;
            this.key = key;
            this.projections = projections;
            this.shape = shape;
        }
    }

    /**
     * One behaviour pinned: its family, the facet a claim on a detail relation reaches it through
     * (the anchor itself for the anchor and its projections), and its shape in the family's kinds.
     */
    record Pin(Family family, String facet, String shape) implements Comparable<Pin> {
        @Override
        public int compareTo(Pin other) {
            return toString().compareTo(other.toString());
        }

        @Override
        public String toString() {
            return family + " " + facet + " " + shape;
        }
    }

    /** Each family's members, by upper-cased relation name, asked of the store's constraints once. */
    private static volatile Map<Anchor, Set<String>> members;

    /**
     * What {@code blocks} pin in the store {@code dsl} reads, as the store stands now, by the
     * relation each claim names: a keystone covers what the relations its page shows pin.
     */
    static Map<String, Set<Pin>> of(DSLContext dsl, List<Block> blocks) {
        var families = members(dsl);
        var pins = new TreeMap<String, Set<Pin>>();
        for (var block : blocks) {
            var relation = block.relation().toUpperCase(Locale.ROOT);
            for (var anchor : Anchor.values()) {
                if (!families.get(anchor).contains(relation)) {
                    continue;
                }
                var facet = relation.equalsIgnoreCase(anchor.anchor)
                        || anchor.projections.stream().anyMatch(relation::equalsIgnoreCase)
                    ? anchor.anchor : block.relation().toLowerCase(Locale.ROOT);
                CorpusExpectations.matched(dsl, block, anchor.key).ifPresent(matched ->
                    dsl.resultQuery(anchor.shape, matched).fetch(0, String.class)
                        .forEach(shape -> pins.computeIfAbsent(block.relation().toLowerCase(Locale.ROOT),
                            ignored -> new TreeSet<>()).add(new Pin(anchor.family, facet, shape))));
            }
        }
        return pins;
    }

    /** Every pin of {@code byRelation}, whichever relation's claim made it. */
    static Set<Pin> all(Map<String, Set<Pin>> byRelation) {
        var all = new TreeSet<Pin>();
        byRelation.values().forEach(all::addAll);
        return all;
    }

    /** Adds {@code more}'s pins to {@code into}'s, relation by relation. */
    static void merge(Map<String, Set<Pin>> into, Map<String, Set<Pin>> more) {
        more.forEach((relation, pins) -> into.computeIfAbsent(relation, ignored -> new TreeSet<>()).addAll(pins));
    }

    /**
     * A page's example: the document {@code @factDocument(path:)} selects and one relation its
     * {@code @factRows(relation:)} shows. It is the keystone of what that document's claims on that
     * relation pin, and of nothing else the document pins.
     */
    record Keystone(String document, String relation) {}

    /**
     * Every keystone the manual names: each example under an {@code examples} folder of
     * {@code docs}, one per relation its {@code @factRows(relation:)} shows from the document its
     * {@code @factDocument(path:)} selects.
     */
    static List<Keystone> keystones(java.nio.file.Path docs) {
        var keystones = new ArrayList<Keystone>();
        try (var walk = java.nio.file.Files.walk(docs)) {
            for (var file : walk.filter(path -> !docs.relativize(path).startsWith("target"))
                    .filter(path -> path.getParent().getFileName().toString().equals("examples"))
                    .filter(path -> path.getFileName().toString().endsWith(".graphql"))
                    .sorted().toList()) {
                for (graphql.language.Definition<?> definition : new graphql.parser.Parser()
                        .parseDocument(java.nio.file.Files.readString(file)).getDefinitions()) {
                    if (!(definition instanceof graphql.language.DirectivesContainer<?> container)) {
                        continue;
                    }
                    for (var document : container.getDirectives("factDocument")) {
                        for (var rows : container.getDirectives("factRows")) {
                            keystones.add(new Keystone(text(document, "path"), text(rows, "relation")));
                        }
                    }
                }
            }
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
        return List.copyOf(keystones);
    }

    private static String text(graphql.language.Directive directive, String argument) {
        return ((graphql.language.StringValue) directive.getArgument(argument).getValue()).getValue();
    }

    /** The behaviours each keystone stands for, from what its document's claims on its relation pin. */
    static Map<Pin, Set<String>> keystones(List<Keystone> keystones, Map<String, Map<String, Set<Pin>>> pinned) {
        var byPin = new TreeMap<Pin, Set<String>>();
        for (var keystone : keystones) {
            pinned.getOrDefault(keystone.document(), Map.of())
                .getOrDefault(keystone.relation().toLowerCase(Locale.ROOT), Set.of())
                .forEach(pin -> byPin.computeIfAbsent(pin, ignored -> new TreeSet<>()).add(keystone.document()));
        }
        return byPin;
    }

    /**
     * The keystone report: each behaviour no page shows, ranked by how many documents pin it, so
     * documentation goes where examples pile up without a page; then the documents that pin no
     * family at all, whose claims are all on relations no family holds.
     */
    static List<String> keystoneReport(Map<String, Map<String, Set<Pin>>> pinned, List<Keystone> keystones,
                                       Set<String> documents) {
        var shown = keystones(keystones, pinned);
        var byPin = new TreeMap<Pin, Set<String>>();
        pinned.forEach((document, byRelation) -> all(byRelation).forEach(pin ->
            byPin.computeIfAbsent(pin, ignored -> new TreeSet<>()).add(document)));
        var lines = new ArrayList<String>();
        lines.add("Behaviours with a keystone: " + shown.size() + " of " + byPin.size());
        shown.forEach((pin, pages) -> lines.add("  " + pin + " shown from " + pages));
        lines.add("Behaviours without a keystone, most pinned first:");
        byPin.entrySet().stream()
            .filter(entry -> !shown.containsKey(entry.getKey()))
            .sorted(java.util.Comparator.<Map.Entry<Pin, Set<String>>>comparingInt(entry -> -entry.getValue().size())
                .thenComparing(Map.Entry::getKey))
            .forEach(entry -> lines.add("  " + entry.getValue().size() + " " + entry.getKey() + " " + entry.getValue()));
        var unpinned = documents.stream()
            .filter(document -> all(pinned.getOrDefault(document, Map.of())).isEmpty())
            .sorted().toList();
        lines.add("Documents pinning no family: " + unpinned.size());
        unpinned.forEach(document -> lines.add("  " + document));
        return lines;
    }

    /**
     * The behaviours pinned by more than one document, each with the documents pinning it. The
     * count the duplicate gate holds is how many pins these documents make beyond one apiece.
     */
    static Map<Pin, Set<String>> duplicates(Map<String, Set<Pin>> pinned) {
        var byPin = new TreeMap<Pin, Set<String>>();
        pinned.forEach((document, pins) ->
            pins.forEach(pin -> byPin.computeIfAbsent(pin, ignored -> new TreeSet<>()).add(document)));
        byPin.values().removeIf(documents -> documents.size() < 2);
        return byPin;
    }

    /** How many pins {@code duplicates} counts beyond the first document of each. */
    static int surplus(Map<Pin, Set<String>> duplicates) {
        return duplicates.values().stream().mapToInt(documents -> documents.size() - 1).sum();
    }

    private static Map<Anchor, Set<String>> members(DSLContext dsl) {
        var known = members;
        if (known == null) {
            synchronized (Pins.class) {
                known = members;
                if (known == null) {
                    known = members = readMembers(dsl);
                }
            }
        }
        return known;
    }

    /**
     * Each family's members: the relations {@code meta_relation} declares at the anchor's grain and
     * that spell its key as the anchor does, which is what a claim's rows are traced back by. A
     * relation keying something finer, a step of a path or a key of a node id, is an anchor of its
     * own and pins no behaviour of this family, however its key extends the anchor's.
     */
    private static Map<Anchor, Set<String>> readMembers(DSLContext dsl) {
        var families = new LinkedHashMap<Anchor, Set<String>>();
        for (var family : Anchor.values()) {
            families.put(family, Set.copyOf(dsl.resultQuery("""
                    SELECT UPPER(m.relation_name)
                      FROM meta_relation a
                      JOIN meta_relation m ON m.grain_name = a.grain_name
                     WHERE a.relation_name = {0}
                       AND (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS c
                             WHERE c.TABLE_NAME = UPPER(m.relation_name)
                               AND c.COLUMN_NAME IN ({1})) = {2}
                    """, DSL.val(family.anchor),
                    DSL.list(family.key.stream().map(column -> DSL.val(column.toUpperCase(Locale.ROOT))).toList()),
                    DSL.val(family.key.size())).fetch(0, String.class)));
        }
        return Map.copyOf(families);
    }

    /** The families' members, for a test naming which relations belong where. */
    static Map<Anchor, Set<String>> membersOf(DSLContext dsl) {
        return members(dsl);
    }

    static List<String> report(Map<Pin, Set<String>> duplicates) {
        var lines = new ArrayList<String>();
        duplicates.forEach((pin, documents) -> lines.add(pin + " pinned by " + documents));
        return lines;
    }
}
