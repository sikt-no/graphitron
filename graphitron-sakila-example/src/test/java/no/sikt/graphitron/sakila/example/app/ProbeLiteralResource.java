package no.sikt.graphitron.sakila.example.app;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

/**
 * The literal half of {@link OverlappingMountTest}'s pair: a root resource whose {@code @Path} has
 * no template, with the two sub-paths the built-in GraphQL resource has. Every method answers with
 * its own identity so the test can see which class served a request.
 */
@Path("/probe")
@Produces(MediaType.TEXT_PLAIN)
public class ProbeLiteralResource {

    static final String IDENTITY = "literal";

    @GET
    public String root() {
        return IDENTITY + ":root";
    }

    @GET
    @Path("schema")
    public String schema() {
        return IDENTITY + ":schema";
    }

    @GET
    @Path("assets/{name}")
    public String asset(@PathParam("name") String name) {
        return IDENTITY + ":asset:" + name;
    }
}
