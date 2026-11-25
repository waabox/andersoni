package org.waabox.andersoni.spring;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.json.JSONObject;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.waabox.andersoni.Andersoni;
import org.waabox.andersoni.Catalog;
import org.waabox.andersoni.metrics.http.AndersoniStatusServlet;

/** Tests for {@link AndersoniHttpAutoConfiguration}. */
class AndersoniHttpAutoConfigurationTest {

  /** A servlet web context with both Andersoni auto-configurations. */
  private final WebApplicationContextRunner webRunner =
      new WebApplicationContextRunner()
          .withConfiguration(AutoConfigurations.of(
              AndersoniAutoConfiguration.class,
              AndersoniHttpAutoConfiguration.class))
          .withUserConfiguration(TestCatalogConfig.class);

  @Test
  void whenContextLoads_givenServletWebApp_shouldRegisterStatusServlet() {
    webRunner.run(context -> {
      final ServletRegistrationBean<?> registration =
          context.getBean("andersoniStatusServlet",
              ServletRegistrationBean.class);
      assertTrue(registration.getServlet() instanceof AndersoniStatusServlet);
      assertEquals(List.of("/andersoni/status"),
          List.copyOf(registration.getUrlMappings()));
    });
  }

  @Test
  void whenContextLoads_givenHttpDisabled_shouldNotRegisterStatusServlet() {
    webRunner.withPropertyValues("andersoni.http.enabled=false")
        .run(context -> assertFalse(
            context.containsBean("andersoniStatusServlet")));
  }

  @Test
  void whenContextLoads_givenModuleNotOnClasspath_shouldNotRegisterStatusServlet() {
    webRunner.withClassLoader(
            new FilteredClassLoader(AndersoniStatusServlet.class))
        .run(context -> assertFalse(
            context.containsBean("andersoniStatusServlet")));
  }

  @Test
  void whenContextLoads_givenNonWebApp_shouldNotRegisterStatusServlet() {
    new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(
            AndersoniAutoConfiguration.class,
            AndersoniHttpAutoConfiguration.class))
        .run(context -> assertFalse(
            context.containsBean("andersoniStatusServlet")));
  }

  @Test
  void whenContextLoads_givenUserDefinedRegistration_shouldKeepIt() {
    webRunner.withUserConfiguration(CustomServletConfig.class)
        .run(context -> assertEquals(List.of("/ops/cache"),
            List.copyOf(context.getBean("andersoniStatusServlet",
                ServletRegistrationBean.class).getUrlMappings())));
  }

  @Test
  void whenGettingStatus_givenStartedNode_shouldReturnJsonWithIndices()
      throws Exception {
    webRunner.withPropertyValues("andersoni.node-id=node-7")
        .run(context -> {
          final MockHttpServletResponse response = new MockHttpServletResponse();
          servlet(context.getBean("andersoniStatusServlet",
              ServletRegistrationBean.class)).service(
                  new MockHttpServletRequest("GET", "/andersoni/status"),
                  response);

          assertEquals(200, response.getStatus());
          assertEquals("application/json;charset=UTF-8",
              response.getContentType());
          final JSONObject json = new JSONObject(
              response.getContentAsString());
          assertEquals("node-7", json.getString("nodeId"));
          final JSONObject catalog = json.getJSONArray("catalogs")
              .getJSONObject(0);
          assertEquals("test-catalog", catalog.getString("name"));
          assertEquals(2, catalog.getInt("itemCount"));
          assertEquals("by-value", catalog.getJSONArray("indices")
              .getJSONObject(0).getString("name"));
        });
  }

  @Test
  void whenPostingStatus_givenStartedNode_shouldRejectMethod()
      throws Exception {
    webRunner.run(context -> {
      final MockHttpServletResponse response = new MockHttpServletResponse();
      servlet(context.getBean("andersoniStatusServlet",
          ServletRegistrationBean.class)).service(
              new MockHttpServletRequest("POST", "/andersoni/status"),
              response);

      assertEquals(405, response.getStatus());
    });
  }

  /**
   * Returns the registered status servlet.
   *
   * @param registration the registration, never null
   *
   * @return the servlet, never null
   */
  private static AndersoniStatusServlet servlet(
      final ServletRegistrationBean<?> registration) {
    return (AndersoniStatusServlet) registration.getServlet();
  }

  /** Registers the status servlet under a custom path. */
  @Configuration
  static class CustomServletConfig {

    /**
     * Creates a registration at a custom path.
     *
     * @param andersoni the node to report on, never null
     *
     * @return the registration, never null
     */
    @Bean
    ServletRegistrationBean<AndersoniStatusServlet> andersoniStatusServlet(
        final Andersoni andersoni) {
      return new ServletRegistrationBean<>(
          new AndersoniStatusServlet(andersoni), "/ops/cache");
    }
  }

  /** Registers one catalog with one index. */
  @Configuration
  static class TestCatalogConfig {

    /**
     * Creates a registrar for a two-item catalog.
     *
     * @return the registrar, never null
     */
    @Bean
    CatalogRegistrar testCatalogRegistrar() {
      return andersoni -> andersoni.register(Catalog.of(String.class)
          .named("test-catalog")
          .data(List.of("hello", "world"))
          .index("by-value").by(s -> s, s -> s)
          .build());
    }
  }
}
