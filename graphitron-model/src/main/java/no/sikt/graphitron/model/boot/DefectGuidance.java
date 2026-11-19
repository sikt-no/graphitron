package no.sikt.graphitron.model.boot;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.jar.JarFile;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * The fix guidance for the store's defect codes: one hand-written AsciiDoc fragment per code,
 * under {@value #DIRECTORY} on this module's classpath, which the store sets as
 * {@code graphitron_defect_type.guidance} when it creates its schema. The guidance is part of the
 * defect type; it is written here rather than in the DDL only because a SQL literal would bury its
 * paragraphs and markup, and every declared code has one.
 *
 * <p>A fragment is named after its code, {@code MUTATION_UPSERT.adoc}. A file whose name starts
 * with an underscore is shared prose, never a fragment of its own: a fragment includes it with a
 * line of its own, {@code include::_name.adoc[]}, and the store holds the fragment with every such
 * include expanded, so a reader of the row needs nothing but the row.
 *
 * <p>A fragment is best-effort guidance until someone reviews it. Review is marked by the
 * fragment's first line, an AsciiDoc line comment {@code // reviewed: <who> <date>}, which never
 * renders; the store strips it from the text and holds what follows the colon as
 * {@code graphitron_defect_type.guidance_reviewed}, NULL where the fragment carries no mark.
 *
 * <p>The store reads one fragment per declared code rather than listing the directory, which works
 * under any class loader. The other direction, a fragment naming no declared code, is what
 * {@link #fragmentCodes()} lists the directory for: the gate over the fragments and the defects
 * reference renderer both read it, so an orphaned fragment fails the build whether or not its
 * tests run.
 */
public final class DefectGuidance {

    /** The classpath directory the fragments live in. */
    public static final String DIRECTORY = "/no/sikt/graphitron/model/defects/";

    private static final Pattern REVIEWED = Pattern.compile("^// reviewed:(.*)$");

    private static final Pattern INCLUDE = Pattern.compile("^include::(_[A-Za-z0-9_.-]+\\.adoc)\\[\\]\\s*$");

    private DefectGuidance() {}

    /**
     * One code's guidance as the store holds it: the text with its includes expanded and its review
     * mark stripped, and who reviewed it and when, null where it is still best effort.
     */
    public record Fragment(String guidance, String reviewed) {}

    /** The fragment for {@code code}, or empty where none is written. */
    public static Optional<Fragment> fragment(String code) {
        return read(DIRECTORY + code + ".adoc").map(text -> parse(code, text));
    }

    /**
     * Splits a fragment's review mark off its text and expands its includes.
     *
     * @throws IllegalStateException where the mark names no reviewer, or a review mark sits anywhere
     *         but the first line, where it would be read as text
     */
    static Fragment parse(String code, String text) {
        String reviewed = null;
        String body = text;
        int firstBreak = text.indexOf('\n');
        String first = firstBreak < 0 ? text : text.substring(0, firstBreak);
        var mark = REVIEWED.matcher(first.strip());
        if (mark.matches()) {
            reviewed = mark.group(1).strip();
            if (reviewed.isEmpty()) {
                throw new IllegalStateException(code + ".adoc's review mark names no reviewer;"
                    + " write // reviewed: <who> <date>");
            }
            body = firstBreak < 0 ? "" : text.substring(firstBreak + 1);
        }
        for (String line : body.split("\n", -1)) {
            if (REVIEWED.matcher(line.strip()).matches()) {
                throw new IllegalStateException(code + ".adoc carries a review mark below its first line;"
                    + " move it to the top");
            }
        }
        return new Fragment(expand(body), reviewed);
    }

    /**
     * The codes the fragment directory names, one per fragment, shared prose left out. Lists the
     * module's own code source, a classes directory or a jar, as {@link CodeStamp} does.
     *
     * @throws IllegalStateException where the code source cannot be found, a gate that cannot see
     *         the fragments having nothing to say about them
     */
    public static Set<String> fragmentCodes() {
        var source = DefectGuidance.class.getProtectionDomain().getCodeSource();
        if (source == null || source.getLocation() == null) {
            throw new IllegalStateException("the defect guidance fragments' code source is unknown");
        }
        Path location;
        try {
            location = Path.of(source.getLocation().toURI());
        } catch (URISyntaxException | IllegalArgumentException e) {
            throw new IllegalStateException("the defect guidance fragments' code source is not a file", e);
        }
        String prefix = DIRECTORY.substring(1);
        var names = new ArrayList<String>();
        if (Files.isDirectory(location)) {
            Path directory = location.resolve(prefix);
            if (Files.isDirectory(directory)) {
                try (Stream<Path> files = Files.list(directory)) {
                    files.map(f -> f.getFileName().toString()).forEach(names::add);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            }
        } else {
            try (var jar = new JarFile(location.toFile())) {
                for (var entry : java.util.Collections.list(jar.entries())) {
                    String name = entry.getName();
                    if (!entry.isDirectory() && name.startsWith(prefix)
                            && name.indexOf('/', prefix.length()) < 0) {
                        names.add(name.substring(prefix.length()));
                    }
                }
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        var codes = new TreeSet<String>();
        for (String name : names) {
            if (name.endsWith(".adoc") && !name.startsWith("_")) {
                codes.add(name.substring(0, name.length() - ".adoc".length()));
            }
        }
        return codes;
    }

    /**
     * Sets every declared code's guidance from its fragment, then makes the column NOT NULL. Run
     * once, as the schema is created, after the DDL's own rows: the codes it reads are those rows.
     *
     * @throws IllegalStateException where a declared code has no fragment: the guidance is part of
     *         the defect type, so no store is created without it
     */
    static void write(Connection connection) throws SQLException {
        var codes = new ArrayList<String>();
        try (PreparedStatement select = connection.prepareStatement(
                "SELECT code FROM graphitron_defect_type ORDER BY code");
             ResultSet rows = select.executeQuery()) {
            while (rows.next()) {
                codes.add(rows.getString(1));
            }
        }
        var missing = new ArrayList<String>();
        try (PreparedStatement update = connection.prepareStatement(
                "UPDATE graphitron_defect_type SET guidance = ?, guidance_reviewed = ? WHERE code = ?")) {
            for (String code : codes) {
                var fragment = fragment(code);
                if (fragment.isEmpty()) {
                    missing.add(code);
                    continue;
                }
                update.setString(1, fragment.get().guidance());
                update.setString(2, fragment.get().reviewed());
                update.setString(3, code);
                update.addBatch();
            }
            update.executeBatch();
        }
        if (!missing.isEmpty()) {
            throw new IllegalStateException("defect codes with no guidance fragment under " + DIRECTORY
                + ": " + String.join(", ", missing) + "; write <CODE>.adoc saying what reaches the"
                + " defect, why the rule holds and what to write instead");
        }
        try (var statement = connection.createStatement()) {
            statement.execute("ALTER TABLE graphitron_defect_type ALTER COLUMN guidance SET NOT NULL");
        }
    }

    private static String expand(String fragment) {
        List<String> lines = new ArrayList<>();
        for (String line : fragment.split("\n", -1)) {
            var include = INCLUDE.matcher(line);
            if (include.matches()) {
                String shared = include.group(1);
                lines.add(read(DIRECTORY + shared)
                    .orElseThrow(() -> new IllegalStateException(
                        "a defect guidance fragment includes " + shared + ", which is not in " + DIRECTORY))
                    .strip());
            } else {
                lines.add(line);
            }
        }
        return String.join("\n", lines).strip();
    }

    private static Optional<String> read(String resource) {
        try (InputStream in = DefectGuidance.class.getResourceAsStream(resource)) {
            return in == null ? Optional.empty()
                : Optional.of(new String(in.readAllBytes(), StandardCharsets.UTF_8).replace("\r\n", "\n"));
        } catch (IOException e) {
            throw new UncheckedIOException("could not read " + resource, e);
        }
    }
}
