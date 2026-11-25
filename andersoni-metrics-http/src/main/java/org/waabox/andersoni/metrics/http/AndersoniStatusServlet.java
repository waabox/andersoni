package org.waabox.andersoni.metrics.http;

import java.io.IOException;
import java.util.Objects;

import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.waabox.andersoni.Andersoni;

/**
 * Serves a node's status document, as rendered by
 * {@link AndersoniStatusJson}, on any Servlet 5+ container.
 *
 * <p>Only {@code GET} (and {@code HEAD}) are answered; every other method
 * gets the container's {@code 405 Method Not Allowed}. The servlet does not
 * pick its own path: map it where it should be served, for example:
 *
 * <pre>{@code
 * context.addServlet(new AndersoniStatusServlet(andersoni))
 *     .addMapping("/andersoni/status");
 * }</pre>
 *
 * <p>The Spring Boot starter registers it at {@code /andersoni/status} on
 * servlet web applications.
 */
public class AndersoniStatusServlet extends HttpServlet {

  /** Serialization version, required by {@link HttpServlet}. */
  private static final long serialVersionUID = 1L;

  /** The node to report on, never null. */
  private final transient Andersoni andersoni;

  /**
   * Creates the servlet.
   *
   * @param theAndersoni the node to report on, never null
   *
   * @throws NullPointerException if theAndersoni is null
   */
  public AndersoniStatusServlet(final Andersoni theAndersoni) {
    andersoni = Objects.requireNonNull(theAndersoni,
        "andersoni must not be null");
  }

  /**
   * Writes the node's status as {@code application/json}.
   *
   * @param request  the request, never null
   * @param response the response, never null
   *
   * @throws IOException if the response cannot be written
   */
  @Override
  protected void doGet(final HttpServletRequest request,
      final HttpServletResponse response) throws IOException {
    final String body = AndersoniStatusJson.render(andersoni);
    response.setStatus(HttpServletResponse.SC_OK);
    response.setContentType("application/json");
    response.setCharacterEncoding("UTF-8");
    response.getWriter().write(body);
  }
}
