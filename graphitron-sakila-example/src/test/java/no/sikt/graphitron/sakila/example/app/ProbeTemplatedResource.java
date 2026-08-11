package no.sikt.graphitron.sakila.example.app;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

/**
 * The templated half of {@link OverlappingMountTest}'s pair, standing in for a consumer that mounts
 * its own GraphQL endpoint under a path parameter. Its {@code @Path} carries one more literal
 * character than {@link ProbeLiteralResource}'s ({@code /probe/} against {@code /probe}), which is
 * the whole reason the two sort the way they do.
 */
@Path("/probe/{p}")
@Produces(MediaType.TEXT_PLAIN)
public class ProbeTemplatedResource {

    static final String IDENTITY = "templated";

    @GET
    public String root(@PathParam("p") String p) {
        return IDENTITY + ":" + p;
    }

    @GET
    @Path("{rest}")
    public String sub(@PathParam("p") String p, @PathParam("rest") String rest) {
        return IDENTITY + ":" + p + "/" + rest;
    }
}
