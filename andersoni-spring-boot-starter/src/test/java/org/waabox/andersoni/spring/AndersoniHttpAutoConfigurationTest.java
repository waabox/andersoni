package org.waabox.andersoni.spring;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import org.json.JSONObject;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.waabox.andersoni.Catalog;
import org.waabox.andersoni.metrics.http.AndersoniStatusJson;

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
  void whenContextLoads_givenServletWebApp_shouldRegisterStatusController() {
    webRunner.run(context ->
        assertTrue(context.containsBean("andersoniStatusController")));
  }

  @Test
  void whenContextLoads_givenHttpDisabled_shouldNotRegisterStatusController() {
    webRunner.withPropertyValues("andersoni.http.enabled=false")
        .run(context -> assertTrue(
            context.getBeansOfType(AndersoniStatusController.class)
                .isEmpty()));
  }

  @Test
  void whenContextLoads_givenModuleNotOnClasspath_shouldNotRegisterStatusController() {
    webRunner.withClassLoader(new FilteredClassLoader(AndersoniStatusJson.class))
        .run(context -> assertTrue(
            context.getBeansOfType(AndersoniStatusController.class)
                .isEmpty()));
  }

  @Test
  void whenContextLoads_givenNonWebApp_shouldNotRegisterStatusController() {
    new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(
            AndersoniAutoConfiguration.class,
            AndersoniHttpAutoConfiguration.class))
        .run(context -> assertTrue(
            context.getBeansOfType(AndersoniStatusController.class)
                .isEmpty()));
  }

  @Test
  void whenGettingStatus_givenStartedNode_shouldReturnJsonWithIndices()
      throws Exception {
    webRunner.withPropertyValues("andersoni.node-id=node-7")
        .run(context -> {
          final MockMvc mvc = MockMvcBuilders.standaloneSetup(
              context.getBean(AndersoniStatusController.class)).build();

          final MvcResult result = mvc.perform(get("/andersoni/status"))
              .andExpect(status().isOk())
              .andExpect(content().contentTypeCompatibleWith(
                  MediaType.APPLICATION_JSON))
              .andReturn();

          final JSONObject json = new JSONObject(
              result.getResponse().getContentAsString());
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
      final MockMvc mvc = MockMvcBuilders.standaloneSetup(
          context.getBean(AndersoniStatusController.class)).build();

      mvc.perform(post("/andersoni/status"))
          .andExpect(status().isMethodNotAllowed());
    });
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
