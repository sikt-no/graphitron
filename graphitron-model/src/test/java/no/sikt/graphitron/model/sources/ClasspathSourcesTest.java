package no.sikt.graphitron.model.sources;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The memo a round seeds, which is the unit the whole shared-identity half rests on.
 *
 * <p>What has to be true is that a seeded entry is answered from the seed and never from the file,
 * because the caller that seeds it is the one that already read the file: the census verified each
 * jar of this round, and the capture asking the same question again is a second pass over the same
 * bytes. The observable chosen here is a value, not a timing. A stopwatch passes on hardware fast
 * enough to hide a second read, and the mutation this file exists to catch, dropping the seed,
 * would sail through such an assertion on every machine in CI.
 */
class ClasspathSourcesTest {

    @Test
    @DisplayName("a seeded entry is answered from the seed, with no file behind it at all")
    void aSeededEntryIsNotRead(@TempDir Path tmp) {
        // Nothing is written here. A path with no file is the strongest form of "not read": an
        // instance that opened it could only answer null, so a non-null answer is proof it did not.
        Path absent = tmp.resolve("resolved-library.jar");
        String supplied = "sha1:" + "a".repeat(40);

        var seeded = new ClasspathSources(Map.of(absent.toString(), supplied));
        assertThat(seeded.stamp(absent))
            .as("the seeded identity, arrived at without opening anything")
            .isEqualTo(supplied);
        assertThat(new ClasspathSources().stamp(absent))
            .as("the same question with no seed has only the file to ask, and there is none")
            .isNull();
    }

    @Test
    @DisplayName("an entry the seed does not name is hashed exactly as before")
    void anUnseededEntryIsStillHashed(@TempDir Path tmp) throws IOException {
        Path seededPath = tmp.resolve("seeded.jar");
        Path plain = Files.writeString(tmp.resolve("plain.jar"), "content");
        var sources = new ClasspathSources(Map.of(seededPath.toString(), "sha1:" + "b".repeat(40)));

        assertThat(sources.stamp(plain))
            .as("a partial seed narrows the population and never suppresses the rest")
            .isEqualTo(ClasspathSources.hash(plain));
    }

    /**
     * A seed value of null is a caller saying it has no identity for that entry, which is the same
     * position as not naming it. Skipped rather than stored, so the memo never holds an absence
     * that could be read back as an answer.
     */
    @Test
    @DisplayName("a null seed value leaves the entry to be hashed")
    void aNullSeedValueIsNotAnAnswer(@TempDir Path tmp) throws IOException {
        Path jar = Files.writeString(tmp.resolve("library.jar"), "content");
        var seeds = new HashMap<String, String>();
        seeds.put(jar.toString(), null);

        assertThat(new ClasspathSources(seeds).stamp(jar)).isEqualTo(ClasspathSources.hash(jar));
    }
}
