package org.waabox.andersoni.example.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import org.waabox.andersoni.Catalog;
import org.waabox.andersoni.spring.CatalogRegistrar;
import org.waabox.andersoni.example.domain.Event;
import org.waabox.andersoni.example.domain.EventRepository;
import org.waabox.andersoni.example.domain.EventSnapshotSerializer;
import org.waabox.andersoni.leader.k8s.K8sLeaseConfig;
import org.waabox.andersoni.leader.k8s.K8sLeaseLeaderElection;
import org.waabox.andersoni.snapshot.s3.S3SnapshotConfig;
import org.waabox.andersoni.snapshot.s3.S3SnapshotStore;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;

import java.net.URI;
import java.time.Duration;
import java.util.function.Function;

/** Spring configuration that defines the Andersoni infrastructure beans
 * for the example application.
 *
 * <p>This configuration provides the following beans that are picked up
 * by the andersoni-spring-boot-starter auto-configuration via
 * {@link org.springframework.beans.factory.ObjectProvider}:
 * <ul>
 *   <li>{@link K8sLeaseLeaderElection} for Kubernetes-based leader
 *       election</li>
 *   <li>{@link S3SnapshotStore} for persisting catalog snapshots to
 *       S3-compatible storage (e.g. MinIO)</li>
 *   <li>{@link CatalogRegistrar} for registering the events catalog
 *       with its indices and refresh policy</li>
 * </ul>
 *
 * <p>Kafka sync is auto-configured by {@code andersoni-spring-sync-kafka}
 * via {@code andersoni.sync.kafka.*} properties in {@code application.yaml}.
 *
 * @author waabox(waabox[at]gmail[dot]com)
 */
@Configuration
public class AndersoniConfig {

  /** Creates a Kubernetes Lease-based leader election strategy.
   *
   * <p>Only the leader node performs scheduled catalog refreshes, avoiding
   * redundant database queries across replicas. The lease is renewed at
   * the configured interval and expires after the configured duration
   * of inactivity.
   *
   * @param nodeId the unique identifier for this node, typically the
   *        pod hostname, never null
   * @param leaseName the name of the K8s Lease resource, never null
   * @param namespace the Kubernetes namespace where the Lease resource
   *        is managed, never null
   * @param renewalIntervalSeconds how often (in seconds) the leader
   *        renews the lease
   * @param leaseDurationSeconds how long (in seconds) the lease is valid
   *        before it expires
   *
   * @return the configured Kubernetes leader election strategy, never null
   */
  @Bean
  public K8sLeaseLeaderElection k8sLeaseLeaderElection(
      @Value("${andersoni.node-id}") final String nodeId,
      @Value("${k8s.lease.name:andersoni-example-leader}")
          final String leaseName,
      @Value("${k8s.lease.namespace:andersoni-example}")
          final String namespace,
      @Value("${k8s.lease.renewal-interval-seconds:15}")
          final int renewalIntervalSeconds,
      @Value("${k8s.lease.lease-duration-seconds:30}")
          final int leaseDurationSeconds) {

    final K8sLeaseConfig config = K8sLeaseConfig.create(
        leaseName,
        namespace,
        nodeId,
        Duration.ofSeconds(renewalIntervalSeconds),
        Duration.ofSeconds(leaseDurationSeconds));

    return new K8sLeaseLeaderElection(config);
  }

  /** Creates an S3Client bean managed by Spring so its lifecycle
   * (including close) is handled properly.
   *
   * <p>Uses an S3-compatible endpoint (such as MinIO for local development)
   * with static credentials and path-style access.
   *
   * @param endpoint the S3-compatible endpoint URL (e.g.
   *        "http://localhost:9000"), never null
   * @param accessKey the access key for authentication, never null
   * @param secretKey the secret key for authentication, never null
   * @param region the AWS region for the S3 client, never null
   *
   * @return the configured S3 client, never null
   */
  @Bean(destroyMethod = "close")
  S3Client s3Client(
      @Value("${s3.endpoint}") final String endpoint,
      @Value("${s3.access-key}") final String accessKey,
      @Value("${s3.secret-key}") final String secretKey,
      @Value("${s3.region:us-east-1}") final String region) {

    return S3Client.builder()
        .region(Region.of(region))
        .endpointOverride(URI.create(endpoint))
        .credentialsProvider(StaticCredentialsProvider.create(
            AwsBasicCredentials.create(accessKey, secretKey)))
        .forcePathStyle(true)
        .build();
  }

  /** Creates an S3-backed snapshot store for persisting catalog data.
   *
   * <p>Snapshots are stored in the configured bucket under the configured
   * prefix. The S3Client is injected as a Spring-managed bean so it is
   * closed properly on shutdown.
   *
   * @param s3Client the Spring-managed S3 client, never null
   * @param bucket the S3 bucket where snapshots are stored, never null
   * @param region the AWS region for the S3 client, never null
   * @param prefix the key prefix within the bucket, never null
   *
   * @return the configured S3 snapshot store, never null
   */
  @Bean
  S3SnapshotStore s3SnapshotStore(
      final S3Client s3Client,
      @Value("${s3.bucket}") final String bucket,
      @Value("${s3.region:us-east-1}") final String region,
      @Value("${s3.prefix:andersoni/}") final String prefix) {

    final S3SnapshotConfig config = S3SnapshotConfig.builder()
        .bucket(bucket)
        .region(Region.of(region))
        .prefix(prefix)
        .s3Client(s3Client)
        .build();

    return new S3SnapshotStore(config);
  }

  /** Creates a catalog registrar that registers the events catalog with
   * the Andersoni instance.
   *
   * <p>The events catalog is configured with:
   * <ul>
   *   <li>Three hash indices: by-sport, by-venue, and by-status for fast
   *       equality lookups on the most common query dimensions</li>
   *   <li>Two sorted indices: by-start-time for date range queries and
   *       by-name for text search (startsWith, endsWith, contains)</li>
   *   <li>A Jackson-based snapshot serializer for S3 persistence</li>
   *   <li>A 5-minute refresh interval for periodic data reloading</li>
   * </ul>
   *
   * @param repository the event repository used to load data from the
   *        database, never null
   *
   * @return the catalog registrar that registers the events catalog,
   *         never null
   */
  @Bean
  public CatalogRegistrar eventsCatalogRegistrar(
      final EventRepository repository) {

    return andersoni -> {
      final Catalog<Event> catalog = Catalog.of(Event.class)
          .named("events")
          .loadWith(repository::findAll)
          .index("by-sport").by(Event::getSport, Function.identity())
          .index("by-venue").by(Event::getVenue, Function.identity())
          .index("by-status").by(Event::getStatus, Function.identity())
          .indexSorted("by-start-time")
              .by(Event::getStartTime, Function.identity())
          .indexSorted("by-name")
              .by(Event::getName, Function.identity())
          .serializer(new EventSnapshotSerializer())
          .refreshEvery(Duration.ofMinutes(5))
          .build();

      andersoni.register(catalog);
    };
  }
}
