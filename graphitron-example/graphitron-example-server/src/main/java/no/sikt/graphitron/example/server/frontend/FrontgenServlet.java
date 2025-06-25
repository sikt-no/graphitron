package no.sikt.graphitron.example.server.frontend;

import com.vaadin.quarkus.QuarkusVaadinServlet;
import jakarta.servlet.annotation.WebServlet;

@WebServlet(urlPatterns = "/frontgen/*", name = "FrontgenServlet", asyncSupported = true)
public class FrontgenServlet extends QuarkusVaadinServlet {

}
