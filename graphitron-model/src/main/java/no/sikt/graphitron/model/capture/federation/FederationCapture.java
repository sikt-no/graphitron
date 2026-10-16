package no.sikt.graphitron.model.capture.federation;

import com.apollographql.federation.graphqljava.FederationDirectives;
import com.apollographql.federation.graphqljava.directives.LinkDirectiveProcessor;
import com.apollographql.federation.graphqljava.exceptions.UnsupportedLinkImportException;
import graphql.language.DirectiveDefinition;
import graphql.language.NamedNode;
import graphql.schema.idl.SchemaParser;
import no.sikt.graphitron.model.read.SourceStamp;
import no.sikt.graphitron.model.schema.federation.FederationSpec;
import no.sikt.graphitron.model.sink.Progress;
import org.jooq.DSLContext;

import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static no.sikt.graphitron.model.Tables.FEDERATION_VERSION;
import static no.sikt.graphitron.model.Tables.FEDERATION_VERSION_IMPORT;
import static no.sikt.graphitron.model.Tables.FEDERATION_WRITTEN_VERSION;
import static no.sikt.graphitron.model.Tables.STORE_GRAPH_SOURCE;
import static no.sikt.graphitron.model.Tables.STORE_SOURCE;

/**
 * The federation gatherer: the specification versions federation-jvm supports, and what each lets
 * a link import, read from the library itself so nothing graphitron maintains lists federation's
 * vocabulary.
 *
 * <p>The library is the source, named by its group and artifact and stamped by the jar its classes
 * were loaded from. The jar carries no Maven metadata to name its release by, so its content is its
 * identity: a stamp the store already holds is not read again, and a changed one is read whole,
 * the old rows going first. Where the classes come from no jar the stamp is null, which reads as
 * changed, on {@code store_source}'s terms.
 *
 * <p>Beside them, the version graphitron writes when it adds a link of its own, from
 * {@link FederationSpec#URL}: graphitron's choice rather than the library's, recorded under the
 * library so the database refuses a version the library does not support.
 *
 * <p>Both readings go through the library's public API. The versions are probed upward from v2.0
 * until it refuses one. A version's imports are the names its definitions carry, each tried as an
 * import, since several versions share one definition file and the library refuses a directive
 * younger than the version a link names.
 */
public final class FederationCapture {

    private FederationCapture() {}

    /** This gatherer's row in {@code store_graph_progress}. */
    private static final String GATHERER = "federation";

    /** The source kind a library graphitron itself depends on is registered under. */
    private static final String LIBRARY = "LIBRARY";

    /** The library's source name: its group and artifact, which is what stays put across releases. */
    public static final String SOURCE_NAME =
        "com.apollographql.federation:federation-graphql-java-support";

    /**
     * Makes {@code graphName} claim the federation library on its classpath, reading the library's
     * versions and imports where the store does not hold them for the jar now loaded, in one
     * transaction.
     */
    public static void capture(DSLContext dsl, String graphName, LocalDateTime readAt) {
        Progress.started(dsl, graphName, GATHERER);
        dsl.transaction(tx -> gather(tx.dsl(), graphName, stamp(),
            FederationSpec.URL.substring(FederationSpec.SPEC_PREFIX.length()), readAt));
        Progress.completed(dsl, graphName, GATHERER);
    }

    static void gather(DSLContext dsl, String graphName, String stamp, String writtenVersion,
                       LocalDateTime readAt) {
        var s = STORE_SOURCE;
        String held = dsl.select(s.STAMP).from(s).where(s.SOURCE_NAME.eq(SOURCE_NAME)).fetchOne(s.STAMP);
        dsl.insertInto(s, s.SOURCE_NAME, s.SOURCE_KIND, s.LAST_SEEN)
            .values(SOURCE_NAME, LIBRARY, readAt)
            .onDuplicateKeyUpdate()
            .set(s.LAST_SEEN, readAt)
            .execute();
        var m = STORE_GRAPH_SOURCE;
        dsl.insertInto(m, m.GRAPH_NAME, m.SOURCE_NAME, m.READ_AT)
            .values(graphName, SOURCE_NAME, readAt)
            .onDuplicateKeyIgnore()
            .execute();

        if (stamp == null || !stamp.equals(held)) {
            read(dsl, stamp, readAt);
        }
        // Every reading, the library unchanged or not: a graphitron upgrade can change the version
        // it writes without the library changing at all.
        var w = FEDERATION_WRITTEN_VERSION;
        dsl.insertInto(w, w.SOURCE_NAME, w.VERSION)
            .values(SOURCE_NAME, writtenVersion)
            .onDuplicateKeyUpdate()
            .set(w.VERSION, writtenVersion)
            .execute();
    }

    /** Reads the library whole, the old rows going first, and stamps it once the new rows are in. */
    private static void read(DSLContext dsl, String stamp, LocalDateTime readAt) {
        var s = STORE_SOURCE;
        // The old versions go first, their imports and the written version by cascade.
        var v = FEDERATION_VERSION;
        var i = FEDERATION_VERSION_IMPORT;
        dsl.deleteFrom(v).where(v.SOURCE_NAME.eq(SOURCE_NAME)).execute();
        for (var entry : versions().entrySet()) {
            dsl.insertInto(v, v.SOURCE_NAME, v.VERSION).values(SOURCE_NAME, entry.getKey()).execute();
            for (String name : entry.getValue()) {
                dsl.insertInto(i, i.SOURCE_NAME, i.VERSION, i.NAME)
                    .values(SOURCE_NAME, entry.getKey(), name)
                    .execute();
            }
        }
        dsl.update(s)
            .set(s.STAMP, stamp)
            .set(s.READ_AT, readAt)
            .where(s.SOURCE_NAME.eq(SOURCE_NAME))
            .execute();
    }

    /** Every version the library supports, in order, with the names its link may import. */
    static Map<String, Set<String>> versions() {
        var versions = new LinkedHashMap<String, Set<String>>();
        for (int minor = 0; ; minor++) {
            String version = "v2." + minor;
            // The library's list is raw, so each element is narrowed where it is read.
            List<?> definitions;
            try {
                definitions = FederationDirectives.loadFederationSpecDefinitions(
                    FederationSpec.SPEC_PREFIX + version);
            } catch (RuntimeException refused) {
                return versions;
            }
            var imports = new LinkedHashSet<String>();
            for (Object definition : definitions) {
                String name = definition instanceof DirectiveDefinition directive
                    ? "@" + directive.getName() : ((NamedNode<?>) definition).getName();
                if (importable(version, name)) {
                    imports.add(name);
                }
            }
            versions.put(version, imports);
        }
    }

    /** Whether a link naming {@code version} may import {@code name}, as the library answers it. */
    private static boolean importable(String version, String name) {
        var registry = new SchemaParser().parse("extend schema @link(url: \""
            + FederationSpec.SPEC_PREFIX + version + "\", import: [\"" + name + "\"])");
        try {
            LinkDirectiveProcessor.loadFederationImportedDefinitions(registry).toList();
            return true;
        } catch (UnsupportedLinkImportException refused) {
            return false;
        }
    }

    /**
     * The content of the jar the library's classes were loaded from, or null where they came from
     * something that is not a regular file to hash.
     */
    static String stamp() {
        try {
            var source = FederationDirectives.class.getProtectionDomain().getCodeSource();
            if (source == null) {
                return null;
            }
            Path jar = Path.of(source.getLocation().toURI());
            return Files.isRegularFile(jar) ? SourceStamp.ofFile(jar) : null;
        } catch (URISyntaxException | RuntimeException unlocatable) {
            return null;
        }
    }
}
