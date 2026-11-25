package org.waabox.andersoni.metrics.http;

import static org.easymock.EasyMock.createMock;
import static org.easymock.EasyMock.expect;
import static org.easymock.EasyMock.replay;
import static org.easymock.EasyMock.verify;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.List;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;
import org.waabox.andersoni.Andersoni;
import org.waabox.andersoni.Catalog;

/** Tests for {@link AndersoniStatusServlet}. */
class AndersoniStatusServletTest {

  @Test
  void whenGettingStatus_givenStartedNode_shouldWriteStatusJson()
      throws Exception {
    final Andersoni andersoni = Andersoni.builder().nodeId("node-3").build();
    andersoni.register(Catalog.of(String.class)
        .named("words")
        .data(List.of("hello", "world"))
        .index("by-value").by(s -> s, s -> s)
        .build());
    andersoni.start();
    try {
      final StringWriter body = new StringWriter();
      final HttpServletRequest request = createMock(HttpServletRequest.class);
      final HttpServletResponse response =
          createMock(HttpServletResponse.class);
      response.setStatus(HttpServletResponse.SC_OK);
      response.setContentType("application/json");
      response.setCharacterEncoding("UTF-8");
      expect(response.getWriter()).andReturn(new PrintWriter(body));
      replay(request, response);

      new AndersoniStatusServlet(andersoni).doGet(request, response);

      verify(request, response);
      final JSONObject json = new JSONObject(body.toString());
      assertEquals("node-3", json.getString("nodeId"));
      final JSONObject catalog = json.getJSONArray("catalogs")
          .getJSONObject(0);
      assertEquals("words", catalog.getString("name"));
      assertEquals(2, catalog.getInt("itemCount"));
    } finally {
      andersoni.stop();
    }
  }

  @Test
  void whenCreating_givenNullAndersoni_shouldThrow() {
    assertThrows(NullPointerException.class,
        () -> new AndersoniStatusServlet(null));
  }
}
