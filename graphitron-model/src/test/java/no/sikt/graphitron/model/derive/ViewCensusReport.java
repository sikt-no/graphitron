package no.sikt.graphitron.model.derive;

import no.sikt.graphitron.model.test.FactStores;
import no.sikt.graphitron.model.test.StatementCensusExtension;
import org.jooq.DSLContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Which views of the fact schema a test run reads, from the statements
 * {@link StatementCensusExtension} recorded, and what follows for the rest: a view no main code is
 * seen reading under test is dead code or a reader without a test. Inert unless {@value #PROPERTY}
 * names the directory a census run wrote, as
 * {@code -Dtest=ViewCensusReport -Dgraphitron.viewCensus=<dir>}.
 *
 * <p>A statement's relations are read through {@link ViewReferences}, the one statement of what a
 * reference is, together with every relation it names as an identifier: {@link ViewReferences} takes
 * a schema-qualified name only, as H2 renders a stored view, where a statement built in Java may name
 * a relation bare. A view is read where a statement names it or a view that is read names it. A
 * statement the parser refuses is counted, its names standing alone. Each view gets one verdict:
 *
 * <ul>
 * <li>{@code read}: a statement main code issued reaches it;</li>
 * <li>{@code test-only}: only statements test code issued reach it, a fact document's claim among
 * them, so it is tested model no main code reads;</li>
 * <li>{@code untested}: no statement reaches it, but main source names it, as a jOOQ constant, a
 * table or record class or a word in a string, or a {@code meta_stage_rule} row dispatches on it;</li>
 * <li>{@code dead}: nothing reaches it and nothing names it.</li>
 * </ul>
 *
 * <p>A view the derivation gatherer owns or under {@code intent_} is marked dissolving, since those
 * take no further investment and an {@code untested} one among them is not a call for a test. Written
 * to {@code target/view-census/views.tsv}, one row per view with its definition's length in
 * characters, which is what its parsed form costs every store, and the views its own body reads,
 * since no view reads another and the count of those that do is the measure that rule is held to.
 */
class ViewCensusReport {

    static final String PROPERTY = "graphitron.viewCensus";

    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");
    private static final Pattern CAMEL = Pattern.compile("(?<=[a-z0-9])(?=[A-Z])");
    private static final String DERIVATION_OWNER = "derivation";

    @Test
    @EnabledIfSystemProperty(named = PROPERTY, matches = ".+")
    void census() throws IOException {
        try (var store = FactStores.inMemory()) {
            report(store.dsl(), Path.of(System.getProperty(PROPERTY)));
        }
    }

    private static void report(DSLContext dsl, Path census) throws IOException {
        var length = new TreeMap<String, Integer>();
        dsl.fetch("SELECT LOWER(TABLE_NAME), LENGTH(VIEW_DEFINITION) FROM INFORMATION_SCHEMA.VIEWS"
                + " WHERE TABLE_SCHEMA = 'PUBLIC'")
            .forEach(row -> length.put(row.get(0, String.class), row.get(1, Integer.class)));
        var views = length.keySet();
        var relations = new TreeSet<String>();
        dsl.fetch("SELECT LOWER(TABLE_NAME) FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_SCHEMA = 'PUBLIC'")
            .forEach(row -> relations.add(row.get(0, String.class)));
        var owner = new TreeMap<String, String>();
        dsl.fetch("SELECT LOWER(relation_name), owner_name FROM meta_relation")
            .forEach(row -> owner.put(row.get(0, String.class), row.get(1, String.class)));
        var dispatched = new TreeSet<String>();
        dsl.fetch("SELECT DISTINCT LOWER(rule_view) FROM meta_stage_rule")
            .forEach(row -> dispatched.add(row.get(0, String.class)));

        // What the run executed, by origin and module.
        var directByMain = new TreeMap<String, Set<String>>();
        var directByTest = new TreeMap<String, Set<String>>();
        int statements = 0;
        int scanned = 0;
        try (Stream<Path> files = Files.list(census)) {
            for (var file : files.filter(f -> f.getFileName().toString().startsWith("statements-")).sorted().toList()) {
                var module = file.getFileName().toString().replaceFirst("^statements-(.*)-\\d+\\.tsv$", "$1");
                for (var line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                    var fields = line.split("\t", 3);
                    if (fields.length < 3) {
                        continue;
                    }
                    statements++;
                    // The parse, and the names besides: ViewReferences takes only a schema-qualified
                    // name for a relation, as H2 renders a stored view, and a statement built in Java
                    // may name one bare, as table(name(...)) renders it. A relation's name is no alias.
                    Set<String> named = new TreeSet<>(names(fields[2], relations));
                    try {
                        named.addAll(ViewReferences.relationsReadBy(dsl, dsl.parser().parseQuery(fields[2])));
                    } catch (RuntimeException unparsed) {
                        scanned++;
                    }
                    var target = fields[0].equals("test") ? directByTest : directByMain;
                    for (var relation : named) {
                        var name = relation.toLowerCase(Locale.ROOT);
                        if (relations.contains(name)) {
                            target.computeIfAbsent(name, v -> new TreeSet<>()).add(module);
                        }
                    }
                }
            }
        }
        var readByMain = lower(ViewReferences.viewsEvaluatedBy(dsl, upper(directByMain.keySet())));
        var readByTest = lower(ViewReferences.viewsEvaluatedBy(dsl, upper(directByTest.keySet())));

        var mainReferences = mainReferences(views);
        var rows = new ArrayList<String>();
        rows.add("view\tverdict\tdissolving\tchars\treads_views\tmain_modules\ttest_modules\tnamed_in_main");
        int viewUponView = 0;
        var tally = new TreeMap<String, Integer>();
        var chars = new TreeMap<String, Long>();
        for (var view : views) {
            String verdict;
            if (readByMain.contains(view)) {
                verdict = "read";
            } else if (readByTest.contains(view)) {
                verdict = "test-only";
            } else if (mainReferences.containsKey(view) || dispatched.contains(view)) {
                verdict = "untested";
            } else {
                verdict = "dead";
            }
            boolean dissolving = view.startsWith("intent_") || DERIVATION_OWNER.equals(owner.get(view));
            tally.merge(verdict + (dissolving ? "/dissolving" : ""), 1, Integer::sum);
            chars.merge(verdict, (long) length.get(view), Long::sum);
            var named = new ArrayList<>(mainReferences.getOrDefault(view, Set.of()));
            if (dispatched.contains(view)) {
                named.add("meta_stage_rule");
            }
            var readsViews = new TreeSet<String>();
            for (var relation : ViewReferences.relationsReadBy(dsl, view)) {
                var name = relation.toLowerCase(Locale.ROOT);
                if (views.contains(name) && !name.equals(view)) {
                    readsViews.add(name);
                }
            }
            if (!readsViews.isEmpty()) {
                viewUponView++;
            }
            rows.add(String.join("\t", view, verdict, dissolving ? "yes" : "", Integer.toString(length.get(view)),
                String.join(",", readsViews),
                String.join(",", directByMain.getOrDefault(view, Set.of())),
                String.join(",", directByTest.getOrDefault(view, Set.of())),
                String.join(",", named.subList(0, Math.min(4, named.size())))));
        }
        Path report = Path.of("target", "view-census");
        Files.createDirectories(report);
        Files.write(report.resolve("views.tsv"), rows, StandardCharsets.UTF_8);
        System.out.println("VIEW-CENSUS statements " + statements + " (" + scanned + " scanned, not parsed), views "
            + views.size() + ", " + tally + ", characters " + chars + ", views reading a view " + viewUponView);
        rows.stream().skip(1).filter(r -> !r.contains("\tread\t"))
            .forEach(r -> System.out.println("VIEW-CENSUS " + r));
    }

    private static Set<String> upper(Set<String> names) {
        var result = new TreeSet<String>();
        names.forEach(name -> result.add(name.toUpperCase(Locale.ROOT)));
        return result;
    }

    private static Set<String> lower(Set<String> names) {
        var result = new TreeSet<String>();
        names.forEach(name -> result.add(name.toLowerCase(Locale.ROOT)));
        return result;
    }

    /** The members of {@code among} that {@code text} names as an identifier, quoted or bare. */
    private static Set<String> names(String text, Set<String> among) {
        var found = new TreeSet<String>();
        Matcher m = IDENTIFIER.matcher(text.replace("\"", " "));
        while (m.find()) {
            var name = m.group().toLowerCase(Locale.ROOT);
            if (among.contains(name)) {
                found.add(name);
            }
        }
        return found;
    }

    /**
     * Where main source of any module names each view: a jOOQ constant ({@code GRAPHITRON_X}), a
     * table or record class ({@code GraphitronX}, {@code GraphitronXRecord}) or a word in a string,
     * comments left out, since a javadoc mention reads nothing.
     */
    private static Map<String, Set<String>> mainReferences(Set<String> views) throws IOException {
        var found = new TreeMap<String, Set<String>>();
        Path root = Path.of("..").toAbsolutePath().normalize();
        try (Stream<Path> modules = Files.list(root)) {
            for (var module : modules.filter(m -> Files.isDirectory(m.resolve("src/main/java"))).toList()) {
                try (Stream<Path> sources = Files.walk(module.resolve("src/main/java"))) {
                    for (var source : sources.filter(f -> f.toString().endsWith(".java")).toList()) {
                        var code = withoutComments(Files.readString(source));
                        Matcher m = IDENTIFIER.matcher(code);
                        while (m.find()) {
                            var word = m.group();
                            for (var candidate : List.of(word.toLowerCase(Locale.ROOT),
                                    CAMEL.matcher(word.replaceFirst("Record$", "")).replaceAll("_").toLowerCase(Locale.ROOT))) {
                                if (views.contains(candidate)) {
                                    found.computeIfAbsent(candidate, v -> new LinkedHashSet<>())
                                        .add(module.getFileName() + ":" + source.getFileName());
                                }
                            }
                        }
                    }
                }
            }
        }
        return found;
    }

    /** Java source with its comments blanked and its string and text-block contents kept. */
    static String withoutComments(String source) {
        var out = new StringBuilder(source.length());
        int i = 0;
        int n = source.length();
        while (i < n) {
            char c = source.charAt(i);
            if (source.startsWith("//", i)) {
                while (i < n && source.charAt(i) != '\n') {
                    i++;
                }
            } else if (source.startsWith("/*", i)) {
                int end = source.indexOf("*/", i + 2);
                i = end < 0 ? n : end + 2;
                out.append(' ');
            } else if (source.startsWith("\"\"\"", i)) {
                int end = source.indexOf("\"\"\"", i + 3);
                end = end < 0 ? n : end + 3;
                out.append(source, i, end);
                i = end;
            } else if (c == '"' || c == '\'') {
                int j = i + 1;
                while (j < n && source.charAt(j) != c && source.charAt(j) != '\n') {
                    j += source.charAt(j) == '\\' ? 2 : 1;
                }
                j = Math.min(n, j + 1);
                out.append(source, i, j);
                i = j;
            } else {
                out.append(c);
                i++;
            }
        }
        return out.toString();
    }
}
