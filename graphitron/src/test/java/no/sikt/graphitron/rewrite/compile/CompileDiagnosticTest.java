package no.sikt.graphitron.rewrite.compile;

import no.sikt.graphitron.rewrite.ValidationReport;
import no.sikt.graphitron.rewrite.test.tier.UnitTier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.tools.Diagnostic;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import java.net.URI;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The flattening's own pins: the canonical file spelling shared with the schema channel, javac's
 * sentinel transcription, and the severity projection's totality over {@link Diagnostic.Kind}.
 */
@UnitTier
class CompileDiagnosticTest {

    @Test
    @DisplayName("the flattening spells the file exactly as the schema channel's canonical site does")
    void fileSpellingAgreesWithTheSchemaChannels() {
        String path = "/work/target/generated-sources/graphitron/gen/pkg/FilmFetchers.java";
        var flattened = CompileDiagnostic.from(diagnostic(path, 12, 7, Diagnostic.Kind.ERROR,
            "compiler.err.cant.resolve"));
        assertThat(flattened.file()).isEqualTo(ValidationReport.canonicalUri(path));
    }

    @Test
    @DisplayName("a diagnostic with no source keeps the placeholder, and NOPOS stays -1")
    void sentinelsTranscribeAsJavacsOwnValues() {
        var flattened = CompileDiagnostic.from(diagnostic(null, Diagnostic.NOPOS, Diagnostic.NOPOS,
            Diagnostic.Kind.WARNING, null));
        assertThat(flattened.file()).isEqualTo("(no source)");
        assertThat(flattened.line()).isEqualTo(-1);
        assertThat(flattened.column()).isEqualTo(-1);
        assertThat(flattened.code()).isNull();
    }

    @Test
    @DisplayName("the code passes through typed, kind verbatim")
    void codeAndKindTranscribeVerbatim() {
        var flattened = CompileDiagnostic.from(diagnostic("/gen/A.java", 1, 1,
            Diagnostic.Kind.MANDATORY_WARNING, "compiler.warn.unchecked"));
        assertThat(flattened.kind()).isEqualTo("MANDATORY_WARNING");
        assertThat(flattened.code()).isEqualTo("compiler.warn.unchecked");
    }

    /**
     * The severity projection is total over javac's kinds, which is what fails when javac grows a
     * {@code Kind}: every kind lands in one of the two severities, and {@code ERROR} alone lands
     * in {@code "error"}.
     */
    @Test
    @DisplayName("the severity projection partitions every javac kind")
    void severityProjectionIsTotalOverJavacsKinds() {
        for (Diagnostic.Kind kind : Diagnostic.Kind.values()) {
            var diagnostic = new CompileDiagnostic("file:///gen/A.java", 1, 1, kind.name(), null, "m");
            assertThat(diagnostic.severity())
                .as("severity of kind %s", kind)
                .isIn("error", "warning");
            assertThat(diagnostic.severity())
                .as("only ERROR projects to error (kind %s)", kind)
                .isEqualTo(kind == Diagnostic.Kind.ERROR ? "error" : "warning");
        }
    }

    private static Diagnostic<JavaFileObject> diagnostic(String path, long line, long column,
                                                         Diagnostic.Kind kind, String code) {
        JavaFileObject source = path == null ? null : new SimpleJavaFileObject(
            URI.create("file://" + path), JavaFileObject.Kind.SOURCE) {
            @Override
            public String getName() {
                return path;
            }
        };
        return new Diagnostic<>() {
            @Override
            public Kind getKind() {
                return kind;
            }

            @Override
            public JavaFileObject getSource() {
                return source;
            }

            @Override
            public long getPosition() {
                return Diagnostic.NOPOS;
            }

            @Override
            public long getStartPosition() {
                return Diagnostic.NOPOS;
            }

            @Override
            public long getEndPosition() {
                return Diagnostic.NOPOS;
            }

            @Override
            public long getLineNumber() {
                return line;
            }

            @Override
            public long getColumnNumber() {
                return column;
            }

            @Override
            public String getCode() {
                return code;
            }

            @Override
            public String getMessage(Locale locale) {
                return "cannot find symbol";
            }
        };
    }
}
