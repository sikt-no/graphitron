package no.sikt.graphitron.mcp.rag.docs;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * The pre-embedded docs bundle (R385): the build-time embedding output, packaged in the jar and
 * re-loaded into an in-memory store at warm time. Bundling the pre-embedded <em>tuples</em> rather
 * than a literal Lucene {@code FSDirectory} is the divergence the spec settled: an {@code FSDirectory}
 * cannot be read from inside a jar, so a literal index would force temp-dir extraction at warm. The
 * tuples re-{@code add()} into a {@link no.sikt.graphitron.mcp.rag.LuceneEmbeddingStore#inMemory}
 * store, re-embedding nothing at runtime (vectors are build-time) and needing no temp files.
 *
 * <p><strong>Format.</strong> A header ({@code magic}, {@code version}, embedder {@code dimension},
 * entry {@code count}) followed by a per-chunk stream of {@code (id, embedText, payload, vector)}.
 * Strings carry an explicit byte-length prefix rather than {@link DataOutputStream#writeUTF} so a
 * chunk body past the 64&nbsp;KB modified-UTF8 ceiling still round-trips.
 *
 * <p><strong>The payload is opaque to the store, so its encoding is a private detail here.</strong>
 * The R372 store seam round-trips the {@code payload} verbatim and never parses it; the spec names it
 * "payloadJson", but a JSON parser would widen the quarantined RAG dependency surface for a string
 * only this module ever produces and consumes. So the payload is a dependency-free reversible encoding
 * (URL-safe Base64 fields, the same Base64 posture the MCP page cursor already uses): the chunk's
 * heading path, source path, anchor, and text, each Base64'd so an arbitrary body cannot collide with
 * a separator. {@code sourcePath} and {@code anchor} are recoverable from the {@code id} too, but are
 * carried in the payload so a hit is self-describing without re-splitting the id.
 */
public final class DocsBundle {

    private DocsBundle() {}

    /** Bundle magic + version; a mismatched magic is a packaging error and fails the warm loudly. */
    private static final String MAGIC = "GTRON-DOCS-RAG";
    private static final int VERSION = 1;

    /** Field separator (outside the URL-safe Base64 alphabet, so a field value cannot contain it). */
    private static final char FIELD_SEP = '|';
    /** Heading-path item separator (likewise outside the URL-safe Base64 alphabet). */
    private static final char PATH_SEP = ',';

    private static final Base64.Encoder B64_ENC = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder B64_DEC = Base64.getUrlDecoder();

    /** One bundled chunk: the store inputs ({@code id}, {@code embedText}, {@code vector}) plus its display {@code payload}. */
    public record Entry(String id, String embedText, String payload, float[] vector) {}

    /** A read bundle: the embedder {@code dimension} the store is sized against and the chunk entries. */
    public record Loaded(int dimension, List<Entry> entries) {}

    /** Encodes a chunk's display fields into the opaque store payload. */
    public static String encodePayload(DocChunk chunk) {
        var heading = new StringBuilder();
        for (int i = 0; i < chunk.headingPath().size(); i++) {
            if (i > 0) {
                heading.append(PATH_SEP);
            }
            heading.append(b64(chunk.headingPath().get(i)));
        }
        return b64(chunk.sourcePath()) + FIELD_SEP + b64(chunk.anchor()) + FIELD_SEP
            + b64(chunk.text()) + FIELD_SEP + heading;
    }

    /** Reconstructs the display fields from a hit's opaque payload. */
    public static DocChunk decodePayload(String payload) {
        String[] parts = payload.split("\\" + FIELD_SEP, -1);
        if (parts.length != 4) {
            throw new IllegalArgumentException("malformed docs payload: expected 4 fields, got " + parts.length);
        }
        var headingPath = new ArrayList<String>();
        if (!parts[3].isEmpty()) {
            for (String item : parts[3].split(String.valueOf(PATH_SEP), -1)) {
                headingPath.add(unb64(item));
            }
        }
        return new DocChunk(headingPath, unb64(parts[0]), unb64(parts[1]), unb64(parts[2]));
    }

    /** Writes the header and the chunk stream to {@code out}. The caller owns flushing / closing. */
    public static void write(OutputStream out, int dimension, List<Entry> entries) {
        var data = new DataOutputStream(out);
        try {
            data.writeUTF(MAGIC);
            data.writeInt(VERSION);
            data.writeInt(dimension);
            data.writeInt(entries.size());
            for (Entry e : entries) {
                writeString(data, e.id());
                writeString(data, e.embedText());
                writeString(data, e.payload());
                data.writeInt(e.vector().length);
                for (float v : e.vector()) {
                    data.writeFloat(v);
                }
            }
            data.flush();
        } catch (IOException ex) {
            throw new UncheckedIOException("failed to write docs bundle", ex);
        }
    }

    /** Reads a full bundle. A bad magic / version is a packaging error and throws. */
    public static Loaded read(InputStream in) {
        var data = new DataInputStream(in);
        try {
            verifyHeaderMagic(data);
            int dimension = data.readInt();
            int count = data.readInt();
            var entries = new ArrayList<Entry>(count);
            for (int i = 0; i < count; i++) {
                String id = readString(data);
                String embedText = readString(data);
                String payload = readString(data);
                int vlen = data.readInt();
                var vector = new float[vlen];
                for (int j = 0; j < vlen; j++) {
                    vector[j] = data.readFloat();
                }
                entries.add(new Entry(id, embedText, payload, vector));
            }
            return new Loaded(dimension, List.copyOf(entries));
        } catch (IOException ex) {
            throw new UncheckedIOException("failed to read docs bundle", ex);
        }
    }

    /**
     * Reads only the {@code dimension} from the header, without materialising the chunk stream. The
     * cheap source-of-truth the {@code docs.search} dimension guard reconciles the runtime embedder
     * against.
     */
    public static int readDimension(InputStream in) {
        var data = new DataInputStream(in);
        try {
            verifyHeaderMagic(data); // consumes magic + version; dimension is next
            return data.readInt();
        } catch (IOException ex) {
            throw new UncheckedIOException("failed to read docs bundle header", ex);
        }
    }

    private static void verifyHeaderMagic(DataInputStream data) throws IOException {
        String magic = data.readUTF();
        if (!MAGIC.equals(magic)) {
            throw new IllegalStateException("not a docs bundle (magic=" + magic + ")");
        }
        int version = data.readInt();
        if (version != VERSION) {
            throw new IllegalStateException("unsupported docs bundle version " + version);
        }
    }

    private static void writeString(DataOutputStream data, String s) throws IOException {
        byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
        data.writeInt(bytes.length);
        data.write(bytes);
    }

    private static String readString(DataInputStream data) throws IOException {
        int len = data.readInt();
        byte[] bytes = new byte[len];
        data.readFully(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static String b64(String s) {
        return B64_ENC.encodeToString(s.getBytes(StandardCharsets.UTF_8));
    }

    private static String unb64(String s) {
        return new String(B64_DEC.decode(s), StandardCharsets.UTF_8);
    }
}
