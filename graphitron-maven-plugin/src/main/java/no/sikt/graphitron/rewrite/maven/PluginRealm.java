package no.sikt.graphitron.rewrite.maven;

import org.apache.maven.artifact.Artifact;
import org.apache.maven.model.Dependency;
import org.apache.maven.plugin.descriptor.PluginDescriptor;

import java.io.IOException;
import java.net.URI;
import java.net.URL;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * The one decode of graphitron's plugin realm: the classloader Maven builds for this plugin from
 * its own dependencies plus whatever the consumer lists under {@code <plugin><dependencies>}.
 * Maven's {@link Artifact}s become {@link Row}s here and nowhere else; the dependency-currency
 * reference, the realm check and the codegen backstop's jar listing all project off them.
 *
 * <p>The realm check is a precondition, not an advisory: graphitron must run on the jOOQ it was
 * compiled against, and two jOOQs at different group ids (an edition beside the open-source jar)
 * are never mediated by Maven, so both land in the realm and the consumer's, listed first, answers
 * every {@code org.jooq} class load. {@link #refusal} is the pure decision; the mojo supplies the
 * loader evidence and throws.
 *
 * @param rows             every plugin-realm artifact that resolved to a file
 * @param consumerAdded    the {@code groupId:artifactId} of each entry the consumer's pom declares
 *                         under this plugin's {@code <dependencies>}, in pom order
 * @param pluginArtifactId this plugin's artifactId, for the message
 */
record PluginRealm(List<Row> rows, List<String> consumerAdded, String pluginArtifactId) {

    /** The class file every jOOQ jar carries exactly once, at any edition. */
    static final String JOOQ_MARKER = "org/jooq/Constants.class";

    PluginRealm {
        rows = List.copyOf(rows);
        consumerAdded = List.copyOf(consumerAdded);
    }

    /**
     * One plugin-realm artifact. {@code trail} is Maven's dependency trail from the plugin itself,
     * so its second element is the direct dependency of the plugin that brought this artifact in.
     */
    record Row(String groupId, String artifactId, String version, Path file, List<String> trail) {
        Row {
            trail = trail == null ? List.of() : List.copyOf(trail);
        }

        String coordinate() {
            return groupId + ":" + artifactId;
        }

        String gav() {
            return coordinate() + ":" + version;
        }

        /** The {@code groupId:artifactId} of the plugin's direct dependency this came through, if known. */
        Optional<String> broughtInBy() {
            if (trail.size() < 2) {
                return Optional.empty();
            }
            String[] parts = trail.get(1).split(":");
            return parts.length < 2 ? Optional.empty() : Optional.of(parts[0] + ":" + parts[1]);
        }
    }

    /** The realm of a hand-built mojo: no rows, no consumer entries. */
    static PluginRealm empty() {
        return new PluginRealm(List.of(), List.of(), "graphitron-maven-plugin");
    }

    static PluginRealm decode(PluginDescriptor descriptor) {
        if (descriptor == null) {
            return empty();
        }
        var added = new ArrayList<String>();
        if (descriptor.getPlugin() != null && descriptor.getPlugin().getDependencies() != null) {
            for (Dependency dependency : descriptor.getPlugin().getDependencies()) {
                added.add(dependency.getGroupId() + ":" + dependency.getArtifactId());
            }
        }
        String artifactId = descriptor.getArtifactId() == null
            ? "graphitron-maven-plugin" : descriptor.getArtifactId();
        return new PluginRealm(rows(descriptor.getArtifacts()), added, artifactId);
    }

    /** Maven's artifacts as rows; an artifact with no version is dropped, one with no file kept. */
    static List<Row> rows(Collection<Artifact> artifacts) {
        if (artifacts == null) {
            return List.of();
        }
        var rows = new ArrayList<Row>();
        for (Artifact artifact : artifacts) {
            if (artifact == null || artifact.getVersion() == null) {
                continue;
            }
            Path file = artifact.getFile() == null ? null
                : artifact.getFile().toPath().toAbsolutePath().normalize();
            rows.add(new Row(artifact.getGroupId(), artifact.getArtifactId(), artifact.getVersion(),
                file, artifact.getDependencyTrail()));
        }
        return rows;
    }

    /**
     * Every classpath root {@code loader} can read {@code resource} from: the jar for a
     * {@code jar:} URL, the directory for a {@code file:} one. Distinct, in the loader's order.
     */
    static List<Path> rootsProviding(ClassLoader loader, String resource) {
        var roots = new LinkedHashSet<Path>();
        try {
            for (URL url : Collections.list(loader.getResources(resource))) {
                rootOf(url, resource).ifPresent(roots::add);
            }
        } catch (IOException e) {
            return List.of();
        }
        return List.copyOf(roots);
    }

    private static Optional<Path> rootOf(URL url, String resource) {
        try {
            String spec = url.toString();
            if (spec.startsWith("jar:")) {
                int bang = spec.indexOf("!/");
                if (bang < 0) {
                    return Optional.empty();
                }
                return Optional.of(Path.of(URI.create(spec.substring(4, bang)))
                    .toAbsolutePath().normalize());
            }
            if (spec.startsWith("file:") && spec.endsWith(resource)) {
                return Optional.of(Path.of(URI.create(spec.substring(0, spec.length() - resource.length())))
                    .toAbsolutePath().normalize());
            }
        } catch (IllegalArgumentException | java.nio.file.FileSystemNotFoundException e) {
            return Optional.empty();
        }
        return Optional.empty();
    }

    /** The jOOQ version a loader's {@code org.jooq.Constants} reports, or {@code null} when it has none. */
    static String runtimeJooqVersion(ClassLoader loader) {
        try {
            Object version = Class.forName("org.jooq.Constants", true, loader).getField("VERSION").get(null);
            return version instanceof String s ? s : null;
        } catch (ReflectiveOperationException | LinkageError | RuntimeException e) {
            return null;
        }
    }

    /**
     * Refuses a realm whose jOOQ is not exactly the one graphitron was compiled against, or
     * returns empty. Two arms, both stated over the loader's own evidence rather than over
     * coordinates, so an edition under another group id, an unmediated duplicate and a fat jar
     * embedding jOOQ are all seen:
     *
     * <ul>
     *   <li>more than one root provides {@link #JOOQ_MARKER};</li>
     *   <li>the one root's {@code Constants.VERSION} is not {@code compiledVersion}, which is what
     *       a plugin-block {@code org.jooq:jooq} at another version leaves behind once Maven has
     *       mediated it over graphitron's own.</li>
     * </ul>
     *
     * <p>No root at all is not judged: graphitron could not have started.
     *
     * @param jooqRoots      the roots of the plugin classloader providing {@link #JOOQ_MARKER}
     * @param runtimeVersion the realm's {@code org.jooq.Constants.VERSION}, read reflectively
     * @param compiledVersion the version graphitron was compiled against
     */
    Optional<String> refusal(List<Path> jooqRoots, String runtimeVersion, String compiledVersion) {
        var byFile = rowsByFile();
        if (jooqRoots.size() > 1) {
            var foreign = jooqRoots.stream()
                .filter(root -> !isOurs(byFile.get(root), compiledVersion))
                .toList();
            if (foreign.size() == jooqRoots.size()) {
                // None is recognisably ours (graphitron's own jOOQ was mediated away, or the rows are
                // unavailable): every root but the one answering first is the stray.
                foreign = jooqRoots.subList(1, jooqRoots.size());
            }
            var sentences = new ArrayList<String>();
            var remedies = new LinkedHashSet<String>();
            for (Path root : foreign) {
                Row row = byFile.get(root);
                sentences.add(describe(root, row) + attribution(row));
                remedies.add(remedy(row));
            }
            return Optional.of("graphitron runs on org.jooq:jooq:" + compiledVersion
                + ", but its plugin classloader also holds " + String.join("; and ", sentences)
                + ". Two jOOQs in the plugin classloader make graphitron run on whichever answers"
                + " first.\n\n" + String.join("\n", remedies));
        }
        if (jooqRoots.size() == 1 && runtimeVersion != null && !runtimeVersion.equals(compiledVersion)) {
            Path root = jooqRoots.get(0);
            Row row = byFile.get(root);
            return Optional.of("graphitron was compiled against org.jooq:jooq:" + compiledVersion
                + ", but its plugin classloader holds " + describe(root, row) + attribution(row)
                + " in its place, which reports jOOQ " + runtimeVersion + ". graphitron must run on"
                + " the jOOQ it was compiled against.\n\n" + remedy(row));
        }
        return Optional.empty();
    }

    /**
     * The coordinate of each root that a row accounts for, for the codegen backstop's listing;
     * a root no row accounts for is rendered as its path.
     */
    String describeRoots(List<Path> roots, Collection<Artifact> projectArtifacts) {
        var byFile = rowsByFile();
        for (Row row : rows(projectArtifacts)) {
            if (row.file() != null) {
                byFile.putIfAbsent(row.file(), row);
            }
        }
        return roots.stream()
            .map(root -> "  - " + describe(root, byFile.get(root)))
            .collect(Collectors.joining("\n"));
    }

    private Map<Path, Row> rowsByFile() {
        var byFile = new LinkedHashMap<Path, Row>();
        for (Row row : rows) {
            if (row.file() != null) {
                byFile.putIfAbsent(row.file(), row);
            }
        }
        return byFile;
    }

    private static boolean isOurs(Row row, String compiledVersion) {
        return row != null && row.coordinate().equals("org.jooq:jooq")
            && row.version().equals(compiledVersion);
    }

    private static String describe(Path root, Row row) {
        return row == null ? root.toString() : row.gav();
    }

    /** Where the stray came from, in the consumer's pom terms; empty when nothing says. */
    private String attribution(Row row) {
        String where = "declared under <plugin><dependencies> for " + pluginArtifactId;
        var entry = entryFor(row);
        if (entry.isPresent()) {
            return entry.get().equals(row.coordinate())
                ? " (" + where + ")"
                : " (brought in by " + entry.get() + ", " + where + ")";
        }
        if (!consumerAdded.isEmpty()) {
            return " (brought in by one of the entries " + where + ": "
                + String.join(", ", consumerAdded) + ")";
        }
        return "";
    }

    private String remedy(Row row) {
        String coordinate = row == null ? "the second jOOQ" : row.coordinate();
        var entry = entryFor(row);
        if (entry.isPresent() && entry.get().equals(row.coordinate())) {
            return "Remove " + coordinate + " from the plugin's <dependencies>; graphitron must run on"
                + " its own jOOQ, and already reads the module's compile classpath for your code.";
        }
        String what = entry.orElse("the entry that brings it");
        if (entry.isEmpty() && consumerAdded.isEmpty()) {
            return "Find what puts " + coordinate + " on graphitron's plugin classloader and remove it;"
                + " graphitron must run on its own jOOQ.";
        }
        return "Move " + what + " from the plugin's <dependencies> to the module's own"
            + " <dependencies>; graphitron already reads the module's compile classpath. If it must"
            + " stay in the plugin block, exclude " + coordinate + " from it.";
    }

    /** The consumer-added plugin-block entry the trail names for {@code row}, if any. */
    private Optional<String> entryFor(Row row) {
        if (row == null) {
            return Optional.empty();
        }
        return row.broughtInBy().filter(consumerAdded::contains);
    }
}
