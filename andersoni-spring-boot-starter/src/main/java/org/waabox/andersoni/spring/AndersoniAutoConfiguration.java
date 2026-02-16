package org.waabox.andersoni.spring;

import java.util.List;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.annotation.Bean;
import org.waabox.andersoni.Andersoni;
import org.waabox.andersoni.RetryPolicy;
import org.waabox.andersoni.leader.LeaderElectionStrategy;
import org.waabox.andersoni.metrics.AndersoniMetrics;
import org.waabox.andersoni.snapshot.SnapshotStore;
import org.waabox.andersoni.sync.SyncStrategy;

/**
 * Spring Boot auto-configuration for the Andersoni in-memory cache library.
 *
 * <p>This configuration creates and manages a singleton {@link Andersoni}
 * instance, wiring optional beans for sync strategy, leader election,
 * snapshot storage, metrics, and retry policy. If these beans are not
 * present in the application context, sensible defaults are used.
 *
 * <p>All {@link CatalogRegistrar} beans discovered in the application
 * context are invoked to register their catalogs before the Andersoni
 * lifecycle starts.
 *
 * <p>The Andersoni lifecycle (start/stop) is managed through Spring's
 * {@link SmartLifecycle}, ensuring proper ordering with other lifecycle
 * beans.
 *
 * @author waabox(waabox[at]gmail[dot]com)
 */
@AutoConfiguration
@EnableConfigurationProperties(AndersoniProperties.class)
public class AndersoniAutoConfiguration {

  /** The class logger. */
  private static final Logger log = LoggerFactory.getLogger(
      AndersoniAutoConfiguration.class);

  /**
   * Creates the singleton {@link Andersoni} bean.
   *
   * <p>The builder is configured with values from
   * {@link AndersoniProperties} and any optional strategy beans found
   * in the application context. All discovered {@link CatalogRegistrar}
   * beans are invoked to register their catalogs.
   *
   * @param properties          the configuration properties, never null
   * @param syncStrategyProvider     provider for an optional SyncStrategy bean
   * @param leaderElectionProvider   provider for an optional
   *                                 LeaderElectionStrategy bean
   * @param snapshotStoreProvider    provider for an optional SnapshotStore bean
   * @param metricsProvider          provider for an optional
   *                                 AndersoniMetrics bean
   * @param retryPolicyProvider      provider for an optional RetryPolicy bean
   * @param catalogRegistrars        the list of catalog registrars,
   *                                 may be empty
   *
   * @return the configured Andersoni instance, never null
   */
  @Bean
  public Andersoni andersoni(
      final AndersoniProperties properties,
      final ObjectProvider<SyncStrategy> syncStrategyProvider,
      final ObjectProvider<LeaderElectionStrategy> leaderElectionProvider,
      final ObjectProvider<SnapshotStore> snapshotStoreProvider,
      final ObjectProvider<AndersoniMetrics> metricsProvider,
      final ObjectProvider<RetryPolicy> retryPolicyProvider,
      final List<CatalogRegistrar> catalogRegistrars) {

    requireAtMostOne(syncStrategyProvider, SyncStrategy.class);
    requireAtMostOne(snapshotStoreProvider, SnapshotStore.class);

    final Andersoni.Builder builder = Andersoni.builder();

    final String nodeId = properties.getNodeId();
    if (nodeId != null && !nodeId.isBlank()) {
      builder.nodeId(nodeId);
      log.info("Andersoni configured with node ID: {}", nodeId);
    }

    syncStrategyProvider.ifAvailable(strategy -> {
      builder.syncStrategy(strategy);
      log.info("Andersoni using custom SyncStrategy: {}",
          strategy.getClass().getSimpleName());
    });

    leaderElectionProvider.ifAvailable(strategy -> {
      builder.leaderElection(strategy);
      log.info("Andersoni using custom LeaderElectionStrategy: {}",
          strategy.getClass().getSimpleName());
    });

    snapshotStoreProvider.ifAvailable(store -> {
      builder.snapshotStore(store);
      log.info("Andersoni using custom SnapshotStore: {}",
          store.getClass().getSimpleName());
    });

    metricsProvider.ifAvailable(metrics -> {
      builder.metrics(metrics);
      log.info("Andersoni using custom AndersoniMetrics: {}",
          metrics.getClass().getSimpleName());
    });

    retryPolicyProvider.ifAvailable(policy -> {
      builder.retryPolicy(policy);
      log.info("Andersoni using custom RetryPolicy");
    });

    final Andersoni andersoni = builder.build();

    for (final CatalogRegistrar registrar : catalogRegistrars) {
      registrar.register(andersoni);
      log.debug("Invoked CatalogRegistrar: {}",
          registrar.getClass().getSimpleName());
    }

    log.info("Andersoni created with {} catalog(s) and node ID: {}",
        andersoni.catalogs().size(), andersoni.nodeId());

    return andersoni;
  }

  /**
   * Creates a {@link SmartLifecycle} bean that manages the Andersoni
   * start/stop lifecycle.
   *
   * <p>The lifecycle starts late (phase {@code Integer.MAX_VALUE - 1})
   * to ensure all other beans are initialized first, and stops early
   * for the same reason.
   *
   * @param andersoni the Andersoni instance to manage, never null
   *
   * @return the lifecycle bean, never null
   */
  @Bean
  public SmartLifecycle andersoniLifecycle(final Andersoni andersoni) {
    return new SmartLifecycle() {

      /** Whether the lifecycle is currently running. */
      private volatile boolean running = false;

      @Override
      public void start() {
        log.info("Starting Andersoni lifecycle...");
        andersoni.start();
        running = true;
        log.info("Andersoni lifecycle started successfully.");
      }

      @Override
      public void stop() {
        log.info("Stopping Andersoni lifecycle...");
        andersoni.stop();
        running = false;
        log.info("Andersoni lifecycle stopped.");
      }

      @Override
      public boolean isRunning() {
        return running;
      }

      @Override
      public int getPhase() {
        return Integer.MAX_VALUE - 1;
      }
    };
  }

  /**
   * Validates that at most one bean of the given type is present in the
   * application context.
   *
   * <p>Andersoni supports exactly one instance of each strategy bean. If
   * multiple beans are found, this method throws an
   * {@link IllegalStateException} with a clear error message listing the
   * conflicting bean classes.
   *
   * @param provider the object provider to validate, never null
   * @param type     the bean type for error reporting, never null
   * @param <T>      the bean type
   *
   * @throws IllegalStateException if more than one bean of the given type
   *                               is present
   */
  private <T> void requireAtMostOne(final ObjectProvider<T> provider,
      final Class<T> type) {

    final List<String> beanNames = provider.orderedStream()
        .map(bean -> bean.getClass().getSimpleName())
        .collect(Collectors.toList());

    if (beanNames.size() > 1) {
      throw new IllegalStateException(
          "Andersoni requires at most one " + type.getSimpleName()
              + " bean, but found " + beanNames.size() + ": "
              + String.join(", ", beanNames));
    }
  }
}
