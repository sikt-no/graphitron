package no.sikt.graphitron.roadmap;

import no.sikt.graphitron.model.boot.GraphitronModelStore;
import no.sikt.graphitron.model.catalog.StoreCatalog;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Renders the fact store's schema reference: one AsciiDoc page per relation-name family plus an
 * index, generated at build and never committed, so the DDL stays the only authored source and
 * the reference cannot drift from it.
 *
 * <p>Everything structural is read from the store through {@link StoreCatalog}: the page set,
 * titles, ordering and preambles come from the {@code meta_family} rows, the per-object prose
 * from the {@code COMMENT ON} text, and prefix-less relations render where their
 * {@code meta_prefixless_relation} row says (an empty page means the index, the reference's one
 * cross-family surface). The renderer owns only presentation vocabulary: file names, heading
 * levels, the fixed template phrases. Comment and meta prose interpolates verbatim as AsciiDoc
 * (the renderability gate beside the store's comment-coverage gate holds the accepted subset);
 * spans this class mints itself go out inert via {@link InertSpans}, the module's own line
 * against accidental substitution.
 *
 * <p>Generated-not-committed removes the drift signal the committed fragments get from their
 * verify diff, so the floors here fail the build instead: an empty catalog, a relation on no
 * page or more than one, or a rendered relation or column with blank comment text all throw
 * {@link BuildFailure} rather than rendering a plausible empty reference.
 */
final class SchemaReferencePages {

    private SchemaReferencePages() {}

    /** CLI: {@code render-schema-reference <output-dir>}. Boots a fresh store and renders. */
    static int run(List<String> args) throws IOException {
        if (args.size() != 1) {
            System.err.println("usage: render-schema-reference <output-dir>");
            return 64;
        }
        StoreCatalog catalog;
        try (var store = GraphitronModelStore.open()) {
            catalog = StoreCatalog.read(store.dsl());
        }
        Path out = Path.of(args.get(0)).toAbsolutePath().normalize();
        Map<String, String> pages = render(catalog);
        Files.createDirectories(out);
        for (var page : pages.entrySet()) {
            Files.writeString(out.resolve(page.getKey()), page.getValue());
        }
        System.out.println("render-schema-reference: " + pages.size() + " pages ("
            + catalog.relations().size() + " relations) into " + out);
        return 0;
    }

    /** Renders the whole reference as file name to content, floors included. */
    static Map<String, String> render(StoreCatalog catalog) {
        if (catalog.families().isEmpty() || catalog.relations().isEmpty()) {
            throw new BuildFailure("the schema reference rendered from an empty catalog;"
                + " the store bootstrap or the meta_family roster is broken");
        }
        var blankComments = new ArrayList<String>();
        for (var relation : catalog.relations()) {
            if (isBlank(relation.comment())) {
                blankComments.add(relation.relationName());
            }
            for (var column : relation.columns()) {
                if (isBlank(column.comment())) {
                    blankComments.add(relation.relationName() + "." + column.columnName());
                }
            }
        }
        if (!blankComments.isEmpty()) {
            throw new BuildFailure("relations or columns with blank comment text would render"
                + " an empty reference entry: " + String.join(", ", blankComments));
        }

        Map<String, String> pageByRelation = assignPages(catalog);

        var pages = new LinkedHashMap<String, String>();
        for (var family : catalog.families()) {
            pages.put(pageFile(family.prefix()), familyPage(catalog, family, pageByRelation));
        }
        pages.put("index.adoc", indexPage(catalog, pageByRelation));
        return pages;
    }

    /**
     * Every relation to the file that renders it, exactly one each: the family page by census
     * prefix, the exemption row's page for the prefix-less, the index where that page is empty.
     */
    private static Map<String, String> assignPages(StoreCatalog catalog) {
        var familyPrefixes = catalog.families().stream().map(StoreCatalog.Family::prefix).toList();
        var assignment = new LinkedHashMap<String, String>();
        var unassigned = new ArrayList<String>();
        for (var relation : catalog.relations()) {
            if (relation.familyPrefix().isPresent() && !relation.exempted()) {
                assignment.put(relation.relationName(), pageFile(relation.familyPrefix().get()));
                continue;
            }
            var exemption = catalog.exemptions().stream()
                .filter(x -> x.relationName().equals(relation.relationName()))
                .findFirst();
            if (relation.familyPrefix().isEmpty() && exemption.isPresent()) {
                String page = exemption.get().page()
                    .map(prefix -> {
                        if (!familyPrefixes.contains(prefix)) {
                            throw new BuildFailure("exemption row for "
                                + relation.relationName() + " names a page that is no family: "
                                + prefix);
                        }
                        return pageFile(prefix);
                    })
                    .orElse("index.adoc");
                assignment.put(relation.relationName(), page);
                continue;
            }
            unassigned.add(relation.relationName());
        }
        if (!unassigned.isEmpty()) {
            throw new BuildFailure("relations with no documentation page; the census and the"
                + " exemption rows disagree: " + String.join(", ", unassigned));
        }
        return assignment;
    }

    private static String familyPage(StoreCatalog catalog, StoreCatalog.Family family,
                                     Map<String, String> pageByRelation) {
        var page = new StringBuilder();
        header(page, family.title(), "The " + family.prefix()
            + " family of the graphitron fact store, generated from the DDL's own comments.");
        page.append("Prefix: ").append(InertSpans.monospace(family.prefix())).append(".\n\n");
        page.append(family.definition()).append("\n");

        String file = pageFile(family.prefix());
        for (var relation : catalog.relations()) {
            if (file.equals(pageByRelation.get(relation.relationName()))) {
                renderRelation(page, relation, "==", pageByRelation, catalog);
            }
        }
        page.append("\nxref:index.adoc[← Schema index]\n");
        return page.toString();
    }

    private static String indexPage(StoreCatalog catalog, Map<String, String> pageByRelation) {
        var page = new StringBuilder();
        header(page, "The fact store schema",
            "The graphitron fact store's relations, one page per family, generated from the"
                + " DDL's own comments.");
        page.append("One page per relation-name family, every page generated from the DDL"
            + " (graphitron-model.sql): the family roster and preambles from the meta_family"
            + " rows, the per-object prose from the COMMENT ON text. The DDL is the only"
            + " authored source; where prose elsewhere disagrees with it, the DDL wins.\n");

        page.append("\n== Families\n\n");
        for (var family : catalog.families()) {
            long count = catalog.relations().stream()
                .filter(r -> r.familyPrefix().map(family.prefix()::equals).orElse(false))
                .count();
            page.append(InertSpans.monospace(family.prefix())).append(" xref:")
                .append(pageFile(family.prefix())).append("[").append(family.title())
                .append("] (").append(count).append(count == 1 ? " relation" : " relations")
                .append(")::\n").append(family.definition()).append("\n\n");
        }

        var indexed = catalog.relations().stream()
            .filter(r -> "index.adoc".equals(pageByRelation.get(r.relationName())))
            .toList();
        if (!indexed.isEmpty()) {
            page.append("== Outside every family\n");
            for (var relation : indexed) {
                catalog.exemptions().stream()
                    .filter(x -> x.relationName().equals(relation.relationName()))
                    .findFirst()
                    .ifPresent(x -> page.append("\n").append(x.reason()).append("\n"));
                renderRelation(page, relation, "===", pageByRelation, catalog);
            }
        }
        page.append("\nxref:../index.adoc[← Reference]\n");
        return page.toString();
    }

    private static void header(StringBuilder page, String title, String description) {
        page.append("// Generated by graphitron-roadmap-tool render-schema-reference from the"
            + " booted fact store. Never edit by hand; edit the DDL's COMMENT ON text or the"
            + " meta_ rows instead.\n");
        page.append("= ").append(title).append("\n");
        page.append(":description: ").append(description).append("\n");
        page.append(":!toc:\n\n");
    }

    private static void renderRelation(StringBuilder page, StoreCatalog.Relation relation,
                                       String heading, Map<String, String> pageByRelation,
                                       StoreCatalog catalog) {
        page.append("\n[#").append(relation.relationName()).append("]\n");
        page.append(heading).append(" ").append(relation.relationName())
            .append(relation.view() ? " (view)" : "").append("\n\n");
        page.append(relation.comment()).append("\n\n");

        if (!relation.primaryKey().isEmpty()) {
            page.append("Primary key: ").append(columnTuple(relation.primaryKey())).append(".");
            for (var foreignKey : relation.foreignKeys()) {
                page.append(" Foreign key: ").append(columnTuple(foreignKey.columns()))
                    .append(" references ")
                    .append(relationLink(foreignKey.referencedRelation(), pageByRelation))
                    .append(" ").append(columnTuple(foreignKey.referencedColumns())).append(".");
            }
            for (var check : relation.checks()) {
                page.append(" Check: ").append(InertSpans.monospace(check.clause())).append(".");
            }
            page.append("\n\n");
        }

        page.append(".Columns\n");
        for (var column : relation.columns()) {
            page.append(InertSpans.monospace(column.columnName())).append(" (")
                .append(column.type()).append(column.nullable() ? ", nullable" : ", not null")
                .append(")::\n").append(column.comment()).append("\n");
        }
    }

    private static String relationLink(String relation, Map<String, String> pageByRelation) {
        return "xref:" + pageByRelation.get(relation) + "#" + relation + "[" + relation + "]";
    }

    private static String columnTuple(List<String> columns) {
        return InertSpans.monospace("(" + String.join(", ", columns) + ")");
    }

    private static String pageFile(String prefix) {
        String base = prefix.endsWith("_") ? prefix.substring(0, prefix.length() - 1) : prefix;
        return base + ".adoc";
    }

    private static boolean isBlank(String text) {
        return text == null || text.isBlank();
    }
}
