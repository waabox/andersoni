package org.waabox.andersoni.spring;

import java.util.Objects;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.waabox.andersoni.Andersoni;
import org.waabox.andersoni.metrics.http.AndersoniStatusJson;

/**
 * Exposes the node's status, with per-index detail, at
 * {@code GET /andersoni/status} on the application's own web server.
 *
 * <p>Registered by {@link AndersoniHttpAutoConfiguration}. The document
 * format is described in {@link AndersoniStatusJson}.
 */
@RestController
public class AndersoniStatusController {

  /** The node to report on, never null. */
  private final Andersoni andersoni;

  /**
   * Creates the controller.
   *
   * @param theAndersoni the node to report on, never null
   *
   * @throws NullPointerException if theAndersoni is null
   */
  public AndersoniStatusController(final Andersoni theAndersoni) {
    andersoni = Objects.requireNonNull(theAndersoni,
        "andersoni must not be null");
  }

  /**
   * Returns the node's status as JSON.
   *
   * @return a 200 response with the status document, never null
   */
  @GetMapping(path = "/andersoni/status",
      produces = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<String> status() {
    return ResponseEntity.ok()
        .contentType(MediaType.APPLICATION_JSON)
        .body(AndersoniStatusJson.render(andersoni));
  }
}
