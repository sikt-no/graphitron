package no.sikt.graphitron.model.boot;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.OptionalLong;

/**
 * The eviction half of the fact store's cache: releases the stamped directories under a store home
 * that nothing is using any more, keeping the most recently used ones.
 *
 * <p>{@link GraphitronModelStore#openAt} keeps its file in a subdirectory of the home stamped with
 * the fact schema's hash and the generator version, so a DDL edit or a generator upgrade opens a
 * different file rather than meeting one it cannot read. That is what makes discarding safe, and it
 * is also what makes the directories accumulate: one per stamp the home has ever seen, each of them
 * a cache with no state of record, and nothing removed the one a run stopped using.
 *
 * <p>Two questions, answered by two independent mechanisms, and keeping them apart is the design.
 *
 * <p><strong>What is worth keeping</strong> is answered by recency: the directory the calling run
 * opened, plus the most recently used others up to the retention count. Recency rather than
 * compatibility ("discard the stamps this generator version cannot produce"), which reads as the
 * stronger rule but is wrong in the one case that matters. One home can legitimately alternate
 * between stamps: two checked-out branches whose DDL differs, a module pinning an older plugin
 * version beside modules on the current one, a bisect. Under a compatibility rule each build reaps
 * the other's store and mints several hundred megabytes afresh, which is worse than the accumulation
 * it fixes. Recency degrades gracefully instead: anything in active alternation stays, up to the
 * retention count, whatever the reason for the alternation.
 *
 * <p><strong>What is safe to release</strong> is answered by asking the operating system whether
 * anybody holds the database, and only then. Recency alone would be wrong about exactly the case
 * this exists for: a {@code graphitron:dev} session opened three days ago holds its stamp and has
 * not written it since, so its directory is the oldest in the home while being the one directory in
 * the home that is genuinely in use. {@link #probeUnheld} is that question; the answer is a fact
 * about the instant it ran rather than an inference from age.
 *
 * <p>What this guarantees, in the terms it can actually keep: it cannot fail a caller, whatever it
 * meets and however it fails, so every method here swallows its own trouble and returns what it
 * managed; it never touches a directory another process holds; and it reads and removes nothing
 * outside a directory it has positively recognised as a store's own. What it cannot guarantee is
 * that no run ever goes cold, and no finite retention could: past the retention count a genuine
 * alternation reaps its oldest arm and the next build on that stamp mints afresh, which is eviction
 * working rather than a defect.
 */
public final class StoreReaper {

    /**
     * The prefix every file a store directory may hold begins with, which is the database base name
     * itself: H2 derives the store, its trace log and its temporary files from it, and
     * {@link GraphitronModelStore#MARKER} is named under it for the same reason. A directory holding
     * anything outside the prefix is not this mechanism's business.
     */
    private static final String STORE_PREFIX = GraphitronModelStore.DATABASE;

    /** The file whose presence a candidate is recognised by: H2's own MVStore file. */
    private static final String DATABASE_FILE = STORE_PREFIX + ".mv.db";

    /**
     * What one sweep released: the number of stamped directories and the bytes their files held.
     * Zero for an in-memory store and for every open of a home this JVM has already swept, which is
     * what {@link #report} reads to decide there is nothing to say.
     */
    public record Reaped(int directories, long bytes) {

        private static final Reaped NONE = new Reaped(0, 0L);

        /** Nothing released: no sweep ran, or the sweep found no candidate outside the retention. */
        public static Reaped none() {
            return NONE;
        }

        /**
         * The one sentence a run says about this, or empty when it released nothing. A run that
         * quietly deletes gigabytes out of a person's cache home should say so once, and the
         * sentence lives here rather than at each caller because both openers report the same event
         * and a second copy of the wording would drift from this one.
         *
         * <p>It names the home rather than calling it "this workspace", because a pinned
         * {@code <storeDirectory>} has no workspace segment and a person who has just lost
         * gigabytes is owed the directory they came out of.
         *
         * @param home the store home that was swept, as the caller resolved it
         */
        public Optional<String> report(Path home) {
            if (directories == 0) {
                return Optional.empty();
            }
            return Optional.of("graphitron: released " + directories + " unused fact-store cache"
                + (directories == 1 ? "" : "s") + " (" + humanBytes(bytes) + ") under "
                + abbreviate(home));
        }
    }

    private StoreReaper() {}

    /**
     * Releases the stamped directories under {@code home} that nothing holds and recency does not
     * keep, and reports what it managed.
     *
     * <p>Never throws. A home that does not exist, a home that is a regular file, a directory it
     * cannot list, a probe it cannot open and a deletion the platform refuses are all answered the
     * same way: leave it alone and carry on to the next candidate. A caller learns only what was
     * released, because there is nothing else it could act on.
     *
     * @param home the store home to sweep, whose direct children are the stamped directories
     * @param liveSegment the name of the directory the calling run opened (or would have opened),
     *        spared by name rather than by the lock probe, since a run that fell back to an
     *        in-memory store holds no lock on its own stamp
     * @param retained how many stamped directories to keep in total, {@code liveSegment} included,
     *        so a retention of three spares the live directory and the two most recently used
     *        others. A parameter rather than a constant read here, so a test can exercise the
     *        ordering with small numbers.
     */
    public static Reaped sweep(Path home, String liveSegment, int retained) {
        List<Candidate> candidates;
        try {
            candidates = candidates(home, liveSegment);
        } catch (IOException | RuntimeException e) {
            return Reaped.none();
        }
        candidates.sort(Comparator.comparing(Candidate::lastUsed).reversed());
        int directories = 0;
        long bytes = 0L;
        // The live directory occupies one of the retained slots whether or not it exists yet, which
        // is what makes a retention of three mean three stamps rather than four.
        for (int i = Math.max(retained - 1, 0); i < candidates.size(); i++) {
            OptionalLong released = release(candidates.get(i).directory());
            if (released.isPresent()) {
                directories++;
                bytes += released.getAsLong();
            }
        }
        return new Reaped(directories, bytes);
    }

    /** A directory recognised as a store's own, with the recency the retention orders it by. */
    private record Candidate(Path directory, FileTime lastUsed) {}

    /**
     * The recognised store directories under {@code home}, other than the live one. A home that is
     * not a directory yields none, which covers both a home nothing has created yet and a path a
     * consumer pinned at a regular file.
     */
    private static List<Candidate> candidates(Path home, String liveSegment) throws IOException {
        var candidates = new ArrayList<Candidate>();
        if (!Files.isDirectory(home)) {
            return candidates;
        }
        try (var children = Files.newDirectoryStream(home)) {
            for (Path child : children) {
                if (!child.getFileName().toString().equals(liveSegment)) {
                    recognise(child).ifPresent(candidates::add);
                }
            }
        }
        return candidates;
    }

    /**
     * Whether {@code child} is a directory this mechanism may release, and its recency if so.
     *
     * <p>The home is not always ours: a consumer that pins {@code <storeDirectory>} may point it at
     * a directory holding other things, so "every sibling of the one I opened" is not a safe rule. A
     * candidate is a direct child directory holding {@link #DATABASE_FILE} and nothing else beyond
     * regular files whose names begin with {@link #STORE_PREFIX}.
     *
     * <p>Requiring the database, rather than accepting it or the marker, is what makes two otherwise
     * undefined states inert. <strong>A directory holding only the marker</strong> is reachable and
     * not a corner: {@code GraphitronModelStore}'s own javadoc tells a person that a hand cleanup
     * means removing everything in the directory that starts with the database name, and doing
     * exactly that to a swept cache leaves the marker behind. A rule admitting it would have to take
     * an exclusive lock on a database that is not there, and opening a channel on the database to
     * find out would have this class create the file it was about to delete. <strong>An empty
     * directory</strong> is not a candidate either, which is also what a store being minted by
     * another process looks like for the instant between {@code createDirectories} and H2's first
     * write.
     */
    private static Optional<Candidate> recognise(Path child) {
        if (!Files.isDirectory(child)) {
            return Optional.empty();
        }
        try (var contents = Files.newDirectoryStream(child)) {
            for (Path entry : contents) {
                if (!entry.getFileName().toString().startsWith(STORE_PREFIX)
                    || !Files.isRegularFile(entry)) {
                    return Optional.empty();
                }
            }
            Path database = child.resolve(DATABASE_FILE);
            if (!Files.isRegularFile(database)) {
                return Optional.empty();
            }
            return Optional.of(new Candidate(child, lastUsed(child, database)));
        } catch (IOException | RuntimeException e) {
            // A directory this cannot read is a directory it will not delete. There is no third
            // recency fallback for the same reason: an unreadable candidate is not a candidate.
            return Optional.empty();
        }
    }

    /**
     * When this directory was last opened. The marker is the recorded answer and is preferred:
     * H2's own file times answer "when was this store last written", which is not the same
     * question, a dev session that only reads keeping a store alive without writing it. For a
     * directory with no marker, which is every store predating the marker, the database's own
     * modification time keeps an existing cache sorting sensibly on the first sweep.
     */
    private static FileTime lastUsed(Path directory, Path database) throws IOException {
        Path marker = directory.resolve(GraphitronModelStore.MARKER);
        return Files.getLastModifiedTime(Files.isRegularFile(marker) ? marker : database);
    }

    /**
     * Releases one recognised candidate, reporting the bytes freed, or empty when the candidate was
     * left alone or could not be emptied.
     *
     * <p>The order is fixed and load-bearing. The probe runs first and its channel is closed
     * <em>before</em> the first unlink, even though a POSIX kernel would permit unlinking a file
     * this process holds a channel on: on Windows the JDK does not open channels with
     * {@code FILE_SHARE_DELETE}, so a deletion under a held channel fails with
     * {@code AccessDeniedException}, and Windows is a platform a cache home resolves on. One order
     * that works everywhere beats a platform-conditional one for a mechanism whose failures are
     * invisible by construction. What it gives up is the strength of the proof: between the probe
     * releasing the lock and the unlink landing, another process may open the candidate, and then
     * either the unlink wins and the opener boots cold next run, or the unlink loses and the
     * candidate survives to the next sweep. Every outcome costs warmth, which is the cache's
     * ordinary failure mode, and the window is two syscalls wide against a candidate nobody has
     * opened in at least as many sessions as the retention count.
     *
     * <p>{@link #DATABASE_FILE} is unlinked <em>last</em>, so a deletion that fails part way leaves
     * a directory the next sweep still recognises and retries rather than the marker-only residue
     * recognition has just declared inert. A deletion that gets the database and then fails to
     * remove the directory itself leaves an empty one, which is the harmless end of the same trade.
     */
    private static OptionalLong release(Path directory) {
        Path database = directory.resolve(DATABASE_FILE);
        if (!probeUnheld(database)) {
            return OptionalLong.empty();
        }
        long bytes = 0L;
        try (var contents = Files.newDirectoryStream(directory)) {
            for (Path entry : contents) {
                if (!entry.getFileName().toString().equals(DATABASE_FILE)) {
                    bytes += unlink(entry);
                }
            }
        } catch (IOException | RuntimeException e) {
            // Whatever is left keeps the directory recognisable for the next sweep.
        }
        long databaseBytes = unlink(database);
        if (Files.exists(database)) {
            return OptionalLong.empty();
        }
        try {
            Files.deleteIfExists(directory);
        } catch (IOException | RuntimeException e) {
            // An empty directory holds no database, so the next sweep does not recognise it and
            // nothing reads it again. Cheaper to leave than to report.
        }
        return OptionalLong.of(bytes + databaseBytes);
    }

    /**
     * Whether nobody holds {@code database} at this instant.
     *
     * <p>{@code GraphitronModelStore} refuses H2's {@code AUTO_SERVER}, which means H2 writes no
     * lock file and takes the MVStore's own operating-system lock on the database instead. That
     * lock is exactly the question this needs, and it is askable from outside H2: another process
     * holding the database makes {@code tryLock} return {@code null}; this process holding it
     * through H2 makes {@code tryLock} throw {@link OverlappingFileLockException}, so a store open
     * in the same JVM is refused for the same reason without this class knowing which JVM it is in;
     * and a holder that was killed leaves no residue, the lock being the operating system's and
     * dying with the process. Any answer other than "acquired" leaves the candidate alone.
     */
    private static boolean probeUnheld(Path database) {
        try (FileChannel channel = FileChannel.open(database, StandardOpenOption.WRITE)) {
            FileLock lock = channel.tryLock();
            if (lock == null) {
                return false;
            }
            lock.release();
            return true;
        } catch (OverlappingFileLockException e) {
            return false;
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    /** Removes one file, reporting the bytes it held, or zero when it survived. */
    private static long unlink(Path file) {
        try {
            long size = Files.size(file);
            return Files.deleteIfExists(file) ? size : 0L;
        } catch (IOException | RuntimeException e) {
            return 0L;
        }
    }

    /**
     * The freed size as a person reads it. Decimal units, because that is what a disk-space
     * complaint is phrased in, and one decimal above kilobytes so the number carries a magnitude
     * without pretending to precision the sweep does not have.
     */
    private static String humanBytes(long bytes) {
        String[] units = {"kB", "MB", "GB", "TB"};
        if (bytes < 1000) {
            return bytes + " B";
        }
        double scaled = bytes;
        int unit = -1;
        while (scaled >= 1000 && unit < units.length - 1) {
            scaled /= 1000;
            unit++;
        }
        return String.format(Locale.ROOT, "%.1f %s", scaled, units[unit]);
    }

    /**
     * The home with a leading user home folded to {@code ~}, which is how the path appears in the
     * documentation and how a person recognises their own cache directory in a build log. Left
     * verbatim when it is somewhere else, a pinned home in a workspace being the ordinary case.
     */
    private static String abbreviate(Path home) {
        String path = home.toString();
        String userHome = System.getProperty("user.home");
        if (userHome != null && !userHome.isBlank() && path.startsWith(userHome)) {
            return "~" + path.substring(userHome.length());
        }
        return path;
    }
}
