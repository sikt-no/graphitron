package no.sikt.graphitron.jakarta.rest;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

/**
 * The self-hosted GraphiQL page and its committed bundle assets, as a mountable delegate. Split from
 * {@link GraphqlHttpHandler} because consumers fork on exactly this axis: a resource mounts the
 * execution pipeline, or the playground, or both, and the two share no collaborator beyond the
 * {@link GraphitronApplication#graphiqlEnabled()} gate.
 *
 * <p>A consumer mounting this owes it two things. The page's asset references are rewritten to an
 * absolute prefix derived from the page's own request URI, so {@link #asset(String)} must be served
 * at {@code assets/{name}} relative to whatever path served {@link #page(UriInfo)} or the bundle
 * 404s. And both methods are gated behind {@code graphiqlEnabled()}, so a consumer delegating here
 * inherits that gate rather than declaring its own.
 */
@ApplicationScoped
public class GraphiqlBundle {

    private static final String GRAPHIQL_HTML = loadResource("graphiql.html");

    @Inject GraphitronApplication application;

    /**
     * The GraphiQL page, with its asset references resolved against {@code uriInfo}'s absolute path.
     * Because that path is the concrete request URI with any template parameters already
     * substituted, a page served from {@code /graphql/test} gets {@code /graphql/test/assets/} and a
     * page served from {@code /graphql} gets {@code /graphql/assets/}: the bundle resolves wherever
     * the consumer mounts it, with no configuration.
     *
     * @param uriInfo the current request's URI info, normally injected with {@code @Context}
     * @return {@code 200} with the page as {@code text/html}, or {@code 404} when GraphiQL is off
     */
    public Response page(UriInfo uriInfo) {
        if (!application.graphiqlEnabled()) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }
        // Rewrite the asset-base placeholder to the absolute path of this page's assets endpoint, so
        // the self-hosted bundle resolves wherever the consumer mounts it (/graphql, /api/graphql,
        // /graphql/{environment}, ...). The page URL is the GraphQL endpoint itself; appending
        // "assets/" targets asset(String). Every downstream chunk/worker/font is referenced relative
        // to the entry files, so only the entry references need the absolute base.
        String base = uriInfo.getAbsolutePath().toString();
        if (!base.endsWith("/")) {
            base += "/";
        }
        String html = GRAPHIQL_HTML.replace("{{ASSET_BASE}}", base + "assets/");
        return Response.ok(html, MediaType.TEXT_HTML).build();
    }

    /**
     * Streams a committed GraphiQL bundle asset (built by {@code tools/graphiql-build}) from this
     * package's {@code graphiql/} classpath directory. Vendor-neutral: reads via
     * {@code getResourceAsStream} rather than relying on a container's static-asset serving.
     *
     * <p>The {@code name} is validated against a strict {@code [A-Za-z0-9._-]+} allowlist (and an
     * explicit {@code ..} reject) so it cannot escape the bundle directory, and its extension must
     * map to a known asset media type; anything else, or a missing resource, is a {@code 404}.
     * Gated behind {@link GraphitronApplication#graphiqlEnabled()}, same as the page itself.
     *
     * @param name the bundle-relative asset file name
     * @return {@code 200} with the asset bytes and its media type, or {@code 404}
     */
    public Response asset(String name) {
        if (!application.graphiqlEnabled()) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }
        if (name == null || !name.matches("[A-Za-z0-9._-]+") || name.contains("..")) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }
        String mediaType = assetMediaType(name);
        if (mediaType == null) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }
        try (InputStream in = GraphiqlBundle.class.getResourceAsStream("graphiql/" + name)) {
            if (in == null) {
                return Response.status(Response.Status.NOT_FOUND).build();
            }
            return Response.ok(in.readAllBytes(), mediaType).build();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Maps a bundle asset's extension to its media type. Returns {@code null} for anything the
     * GraphiQL build does not emit, so unknown extensions fall through to a {@code 404}.
     */
    private static String assetMediaType(String name) {
        int dot = name.lastIndexOf('.');
        if (dot < 0) {
            return null;
        }
        return switch (name.substring(dot + 1)) {
            case "js" -> "text/javascript";
            case "css" -> "text/css";
            case "map" -> "application/json";
            case "ttf" -> "font/ttf";
            case "woff" -> "font/woff";
            case "woff2" -> "font/woff2";
            case "svg" -> "image/svg+xml";
            default -> null;
        };
    }

    private static String loadResource(String name) {
        try (InputStream in = GraphiqlBundle.class.getResourceAsStream(name)) {
            if (in == null) {
                throw new IllegalStateException("Missing classpath resource: " + name);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
