package org.waabox.andersoni.spring;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.waabox.andersoni.Andersoni;
import org.waabox.andersoni.Catalog;
import org.waabox.andersoni.sync.SyncStrategy;
import org.waabox.andersoni.sync.RefreshEvent;
import org.waabox.andersoni.sync.RefreshListener;

/**
 * Tests for {@link AndersoniAutoConfiguration}.
 *
 * <p>Uses {@link ApplicationContextRunner} for fast, isolated testing
 * of the auto-configuration without bootstrapping a full Spring Boot
 * application.
 *
 * @author waabox(waabox[at]gmail[dot]com)
 */
class AndersoniAutoConfigurationTest {

  /** The application context runner configured with the auto-configuration. */
  private final ApplicationContextRunner runner = new ApplicationContextRunner()
      .withConfiguration(
          AutoConfigurations.of(AndersoniAutoConfiguration.class));

  /**
   * Verifies that the Andersoni bean is created with default settings when
   * no catalog registrars are defined, and that the node ID is a valid UUID.
   */
  @Test
  void whenContextLoads_givenNoCatalogRegistrars_shouldCreateAndersoniBeanWithDefaults() {
    runner.run(context -> {
      assertNotNull(context.getBean(Andersoni.class));

      final Andersoni andersoni = context.getBean(Andersoni.class);

      // nodeId should be a valid UUID when no custom value is provided
      assertNotNull(andersoni.nodeId());
      UUID.fromString(andersoni.nodeId()); // throws if not valid UUID
    });
  }

  /**
   * Verifies that a CatalogRegistrar bean causes its catalogs to be
   * registered with the Andersoni instance, and that searches work
   * correctly on the registered catalog.
   */
  @Test
  void whenContextLoads_givenCatalogRegistrar_shouldRegisterCatalogs() {
    runner.withUserConfiguration(TestCatalogConfig.class)
        .run(context -> {
          final Andersoni andersoni = context.getBean(Andersoni.class);

          assertNotNull(andersoni);
          assertEquals(1, andersoni.catalogs().size());

          final List<?> results = andersoni.search(
              "test-catalog", "by-value", "hello");
          assertEquals(1, results.size());
          assertEquals("hello", results.get(0));
        });
  }

  /**
   * Verifies that setting the andersoni.node-id property causes
   * the Andersoni instance to use that custom node ID.
   */
  @Test
  void whenContextLoads_givenCustomNodeId_shouldUseIt() {
    runner.withPropertyValues("andersoni.node-id=custom-node-42")
        .run(context -> {
          final Andersoni andersoni = context.getBean(Andersoni.class);
          assertEquals("custom-node-42", andersoni.nodeId());
        });
  }

  /**
   * Verifies that when a custom SyncStrategy bean is defined,
   * it is wired into the Andersoni instance (confirmed by the strategy
   * being started during lifecycle).
   */
  @Test
  void whenContextLoads_givenCustomSyncStrategy_shouldUseIt() {
    runner.withUserConfiguration(TestSyncStrategyConfig.class)
        .run(context -> {
          final Andersoni andersoni = context.getBean(Andersoni.class);
          assertNotNull(andersoni);

          final TestSyncStrategy syncStrategy =
              context.getBean(TestSyncStrategy.class);
          assertTrue(syncStrategy.isStarted(),
              "SyncStrategy should have been started during lifecycle");
        });
  }

  /**
   * Test configuration that provides a CatalogRegistrar with a simple
   * string-based catalog.
   */
  @Configuration(proxyBeanMethods = false)
  static class TestCatalogConfig {

    /**
     * Creates a catalog registrar that registers a test catalog with
     * static string data.
     *
     * @return the catalog registrar, never null
     */
    @Bean
    CatalogRegistrar testCatalogRegistrar() {
      return andersoni -> {
        final Catalog<String> catalog = Catalog.of(String.class)
            .named("test-catalog")
            .data(List.of("hello", "world"))
            .index("by-value").by(s -> s, s -> s)
            .build();
        andersoni.register(catalog);
      };
    }
  }

  /**
   * Test configuration that provides a custom SyncStrategy.
   */
  @Configuration(proxyBeanMethods = false)
  static class TestSyncStrategyConfig {

    /**
     * Creates a test sync strategy bean.
     *
     * @return the test sync strategy, never null
     */
    @Bean
    TestSyncStrategy testSyncStrategy() {
      return new TestSyncStrategy();
    }
  }

  /**
   * A test SyncStrategy that tracks whether start() was called.
   */
  static class TestSyncStrategy implements SyncStrategy {

    /** Whether start() has been called. */
    private boolean started = false;

    /** {@inheritDoc} */
    @Override
    public void publish(final RefreshEvent event) {
      // no-op for testing
    }

    /** {@inheritDoc} */
    @Override
    public void subscribe(final RefreshListener listener) {
      // no-op for testing
    }

    /** {@inheritDoc} */
    @Override
    public void start() {
      started = true;
    }

    /** {@inheritDoc} */
    @Override
    public void stop() {
      started = false;
    }

    /**
     * Returns whether start() was called.
     *
     * @return true if started, false otherwise
     */
    boolean isStarted() {
      return started;
    }
  }
}
