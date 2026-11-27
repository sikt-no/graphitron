package no.sikt.graphitron.model.test;

import no.sikt.graphitron.model.rules.RuleText;
import org.jooq.DSLContext;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.jar.JarFile;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * The stages' rule files, for the gates that still hold a rule to what it may read and how it may be
 * shaped. A rule is its stage's own implementation and no stage reads another's; these gates read
 * every one because what they check binds every stage: that a rule reads nothing a later stage
 * writes and nothing its gatherer may not read, and that H2 can plan it. Lists this module's code
 * source, a classes directory or a jar, under {@link RuleText#ROOT}, as the store reads it.
 */
public final class StageRuleFiles {

    private StageRuleFiles() {}

    /** One stage's rule: the gatherer and stage whose folder holds it, its name and its text. */
    public record RuleFile(String gatherer, String stage, String rule, String text) {}

    private static final Pattern PATH = Pattern.compile("([a-z][a-z0-9-]*)/([a-z][a-z0-9-]*)/([a-z0-9_]+)\\.sql");

    /** Every stage's rule file, by gatherer, stage and rule. */
    public static List<RuleFile> all() {
        var source = RuleText.class.getProtectionDomain().getCodeSource();
        if (source == null || source.getLocation() == null) {
            throw new IllegalStateException("the rule files' code source is unknown");
        }
        Path location;
        try {
            location = Path.of(source.getLocation().toURI());
        } catch (URISyntaxException | IllegalArgumentException e) {
            throw new IllegalStateException("the rule files' code source is not a file", e);
        }
        String prefix = RuleText.ROOT.substring(1);
        var files = new ArrayList<RuleFile>();
        try {
            if (Files.isDirectory(location)) {
                Path root = location.resolve(prefix);
                try (Stream<Path> walk = Files.walk(root)) {
                    for (Path file : walk.filter(Files::isRegularFile).toList()) {
                        String path = root.relativize(file).toString().replace('\\', '/');
                        if (PATH.matcher(path).matches()) {
                            add(files, path, Files.readString(file));
                        }
                    }
                }
            } else {
                try (var jar = new JarFile(location.toFile())) {
                    for (var entry : java.util.Collections.list(jar.entries())) {
                        if (!entry.isDirectory() && entry.getName().startsWith(prefix)
                                && PATH.matcher(entry.getName().substring(prefix.length())).matches()) {
                            try (var in = jar.getInputStream(entry)) {
                                add(files, entry.getName().substring(prefix.length()),
                                    new String(in.readAllBytes(), StandardCharsets.UTF_8));
                            }
                        }
                    }
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        files.sort(Comparator.comparing(RuleFile::gatherer).thenComparing(RuleFile::stage)
            .thenComparing(RuleFile::rule));
        return List.copyOf(files);
    }

    private static void add(List<RuleFile> files, String path, String text) {
        var m = PATH.matcher(path);
        if (m.matches()) {
            files.add(new RuleFile(m.group(1), m.group(2), m.group(3), text.strip()));
        }
    }

    /**
     * Runs {@code body} with a view of each of {@code files} in the store, named as its rule, and
     * drops them after, for a gate whose instrument reads a stored definition: H2 states a view's
     * relations schema-qualified, which is how {@code ViewReferences} tells a relation from an alias.
     */
    public static void withViews(DSLContext dsl, List<RuleFile> files, Runnable body) {
        var created = new ArrayList<String>();
        try {
            for (var file : files) {
                execute(dsl, "CREATE VIEW " + file.rule() + " AS\n" + file.text());
                created.add(file.rule());
            }
            body.run();
        } finally {
            for (int i = created.size() - 1; i >= 0; i--) {
                execute(dsl, "DROP VIEW IF EXISTS " + created.get(i));
            }
        }
    }

    /** Over JDBC, so the rule's text reaches H2 as written rather than as a jOOQ template. */
    private static void execute(DSLContext dsl, String sql) {
        dsl.connection(connection -> {
            try (var statement = connection.createStatement()) {
                statement.execute(sql);
            }
        });
    }
}
