package no.sikt.graphitron.model.boot;

import no.sikt.graphitron.model.boot.StoreReaper.Reaped;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFilePermissions;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The sweep, against homes built by hand. The stamp segment names are arbitrary to the reaper, which
 * is why every case here can name its own directories rather than going through
 * {@link GraphitronModelStore#openAt}: what is under test is the policy (recency and a count), the
 * recognition rule that decides what may be touched at all, and the lock probe that decides what is
 * safe to release.
 *
 * <p>Two properties are worth naming before the cases. The reaper never fails a caller, so every
 * shape it cannot handle is asserted as "reports zero and throws nothing" rather than as an
 * exception. And it reports what it actually removed, so the byte totals here are exact rather than
 * approximate: the count and the size are the feature's whole user surface on an ordinary build, and
 * a report that drifts from the disk is worse than no report.
 */
class StoreReaperTest {

    /** The retention every case states, matching the store's own, unless the case is about it. */
    private static final int RETAINED = 3;

    private static final String LIVE = "live-stamp";

    @Test
    @DisplayName("keeps the live directory and the two most recently used others, releases the rest")
    void keepsTheLiveDirectoryAndTheTwoMostRecentlyUsedOthers(@TempDir Path home) throws IOException {
        storeDirectory(home, LIVE, 10, hoursAgo(1));
        storeDirectory(home, "newest", 100, hoursAgo(2));
        storeDirectory(home, "middle", 200, hoursAgo(3));
        storeDirectory(home, "older", 400, hoursAgo(4));
        storeDirectory(home, "oldest", 800, hoursAgo(5));

        Reaped reaped = StoreReaper.sweep(home, LIVE, RETAINED);

        assertThat(home.resolve(LIVE)).as("the directory this run opened").exists();
        assertThat(home.resolve("newest")).as("the most recently used other").exists();
        assertThat(home.resolve("middle")).as("the second most recently used other").exists();
        assertThat(home.resolve("older")).as("past the retention").doesNotExist();
        assertThat(home.resolve("oldest")).as("past the retention").doesNotExist();
        assertThat(reaped.directories()).isEqualTo(2);
        assertThat(reaped.bytes())
            .as("the bytes the two released directories actually held, marker files included")
            .isEqualTo(400 + 800 + 2 * markerSize());
    }

    /**
     * The live directory is spared by name rather than by the probe, which is the only thing that
     * can spare it in the arms where the run fell back to an in-memory store and holds no lock on
     * its own stamp. Its marker being the oldest in the home is the shape that would break a
     * recency-only rule.
     */
    @Test
    @DisplayName("never releases the live directory, even when its marker is the oldest in the home")
    void neverReleasesTheLiveDirectory(@TempDir Path home) throws IOException {
        storeDirectory(home, LIVE, 10, hoursAgo(100));
        storeDirectory(home, "a", 20, hoursAgo(1));
        storeDirectory(home, "b", 20, hoursAgo(2));
        storeDirectory(home, "c", 20, hoursAgo(3));

        Reaped reaped = StoreReaper.sweep(home, LIVE, 1);

        assertThat(home.resolve(LIVE)).exists();
        assertThat(reaped.directories()).as("a retention of one keeps only the live directory")
            .isEqualTo(3);
    }

    /**
     * The dev-session case in miniature: a directory recency would release, held by a live database.
     * The probe answers it, and this JVM holding the database through H2 is refused for the same
     * reason another process would be, which is what makes the probe portable across the two.
     */
    @Test
    @DisplayName("leaves a candidate alone while its database is open, releases it once closed")
    void leavesAnOpenCandidateAlone(@TempDir Path home) throws IOException, SQLException {
        storeDirectory(home, "a", 20, hoursAgo(1));
        storeDirectory(home, "b", 20, hoursAgo(2));
        Path held = home.resolve("held");
        Files.createDirectories(held);

        try (Connection connection = h2At(held.resolve("store"))) {
            connection.createStatement().execute("CREATE TABLE held (x INT)");

            Reaped whileHeld = StoreReaper.sweep(home, LIVE, 1);

            assertThat(held).as("a database somebody holds is never released").exists();
            assertThat(held.resolve("store.mv.db")).exists();
            assertThat(whileHeld.directories()).as("the two unheld candidates went").isEqualTo(2);
        }

        Reaped afterClose = StoreReaper.sweep(home, LIVE, 1);

        assertThat(held).as("the lock died with the connection").doesNotExist();
        assertThat(afterClose.directories()).isEqualTo(1);
    }

    /**
     * The home is not always ours: a consumer that pins {@code storeDirectory} may point it at a
     * directory holding other things, so every shape here has to survive a sweep untouched. The
     * marker-only directory is the one a documented hand cleanup produces, and the empty one is what
     * a store being minted by another process looks like for an instant.
     */
    @Test
    @DisplayName("leaves alone everything it has not recognised as a store's own directory")
    void leavesUnrecognisedThingsAlone(@TempDir Path home) throws IOException {
        Path withSubdirectory = storeDirectory(home, "with-subdirectory", 20, hoursAgo(10));
        Files.createDirectories(withSubdirectory.resolve("store-sub"));
        Path withStranger = storeDirectory(home, "with-stranger", 20, hoursAgo(10));
        Files.writeString(withStranger.resolve("notes.txt"), "mine");
        Path empty = Files.createDirectories(home.resolve("empty"));
        Path markerOnly = Files.createDirectories(home.resolve("marker-only"));
        Files.writeString(markerOnly.resolve("store.last-used"), Instant.now().toString());
        Path loose = home.resolve("loose-file");
        Files.writeString(loose, "not a directory");

        Reaped reaped = StoreReaper.sweep(home, LIVE, 1);

        assertThat(reaped).isEqualTo(Reaped.none());
        assertThat(withSubdirectory).exists();
        assertThat(withStranger.resolve("notes.txt")).exists();
        assertThat(empty).exists();
        assertThat(markerOnly.resolve("store.last-used")).exists();
        assertThat(loose).exists();
    }

    @Test
    @DisplayName("reports zero and throws nothing for a home that is missing or is a regular file")
    void answersAnUnusableHomeWithZero(@TempDir Path tmp) throws IOException {
        Path missing = tmp.resolve("never-created");
        Path file = tmp.resolve("a-file");
        Files.writeString(file, "not a home");

        assertThat(StoreReaper.sweep(missing, LIVE, RETAINED)).isEqualTo(Reaped.none());
        assertThat(StoreReaper.sweep(file, LIVE, RETAINED)).isEqualTo(Reaped.none());
        assertThat(file).as("a pinned home that is a file is read, not rewritten").exists();
    }

    /**
     * The total form of the property the unlink order protects: the reaper never leaves a residue its
     * own recognition rule would reject. {@code store.mv.db} is unlinked last, so a deletion that
     * fails part way leaves a directory the next sweep still recognises and retries, rather than the
     * marker-only residue recognition has declared inert.
     *
     * <p>POSIX-only, because the shape being asserted is a deletion that fails part way and there is
     * no portable way to arrange one. Skipped for a superuser too, and for the same reason rather
     * than a different one: the arrangement is a directory permission, and a superuser bypasses it,
     * so there is no failure to observe. That is the ordinary case in a container-based agent
     * session; GitHub's hosted runners execute as an unprivileged user, so this does run in CI.
     */
    @Test
    @DisplayName("a candidate it cannot empty stays recognisable and is reported as not released")
    void aCandidateItCannotEmptyStaysRecognisable(@TempDir Path home) throws IOException {
        assumeTrue(FileSystems.getDefault().supportedFileAttributeViews().contains("posix"),
            "arranging a part-way deletion needs POSIX directory permissions");
        assumeTrue(canBeDeniedByDirectoryPermissions(home),
            "a superuser bypasses the directory permission this case rests on");
        Path stuck = storeDirectory(home, "stuck", 40, hoursAgo(10));
        Files.setPosixFilePermissions(stuck, PosixFilePermissions.fromString("r-x------"));
        try {
            Reaped reaped = StoreReaper.sweep(home, LIVE, 1);

            assertThat(reaped).as("nothing was freed, so nothing is reported")
                .isEqualTo(Reaped.none());
            assertThat(stuck.resolve("store.mv.db"))
                .as("the database is unlinked last, so a failed sweep leaves the directory a "
                    + "candidate the next sweep retries")
                .exists();
        } finally {
            Files.setPosixFilePermissions(stuck, PosixFilePermissions.fromString("rwx------"));
        }
    }

    /**
     * Whether an unwritable directory actually refuses a deletion for this process. Asked rather
     * than inferred from the user name, since the question is about the effective privilege and a
     * name is a proxy for it.
     */
    private static boolean canBeDeniedByDirectoryPermissions(Path parent) throws IOException {
        Path probe = Files.createDirectories(parent.resolve("permission-probe"));
        Path file = Files.writeString(probe.resolve("f"), "x");
        Files.setPosixFilePermissions(probe, PosixFilePermissions.fromString("r-x------"));
        try {
            return !Files.deleteIfExists(file);
        } catch (IOException e) {
            return true;
        } finally {
            Files.setPosixFilePermissions(probe, PosixFilePermissions.fromString("rwx------"));
            Files.deleteIfExists(file);
            Files.deleteIfExists(probe);
        }
    }

    /**
     * A store directory as the reaper recognises one: the database H2 would keep, at the given size,
     * plus a marker whose modification time is the recency the retention sorts on.
     */
    private static Path storeDirectory(Path home, String segment, int databaseBytes, Instant lastUsed)
        throws IOException {
        Path directory = Files.createDirectories(home.resolve(segment));
        Files.write(directory.resolve("store.mv.db"), new byte[databaseBytes]);
        Path marker = directory.resolve("store.last-used");
        Files.writeString(marker, MARKER_TEXT);
        Files.setLastModifiedTime(marker, FileTime.from(lastUsed));
        return directory;
    }

    /** What every marker this class writes holds, so a released directory's byte total is exact. */
    private static final String MARKER_TEXT = "2026-08-27T00:00:00Z";

    private static long markerSize() {
        return MARKER_TEXT.length();
    }

    private static Instant hoursAgo(int hours) {
        return Instant.now().minus(hours, ChronoUnit.HOURS);
    }

    /**
     * An H2 file database at {@code base}, opened the way the store opens one: no
     * {@code AUTO_SERVER}, so H2 writes no lock file and takes the MVStore's own operating-system
     * lock, which is the lock the probe asks about.
     */
    private static Connection h2At(Path base) throws SQLException {
        JdbcDataSource source = new JdbcDataSource();
        source.setURL("jdbc:h2:file:" + base.toAbsolutePath());
        return source.getConnection();
    }
}
