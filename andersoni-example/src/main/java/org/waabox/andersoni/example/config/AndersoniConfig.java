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
import org.waabox.andersoni.sync.kafka.KafkaSyncConfig;
import org.waabox.andersoni.sync.kafka.KafkaSyncStrategy;

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
 *   <li>{@link KafkaSyncStrategy} for cross-node cache synchronization
 *       via Kafka</li>
 *   <li>{@link K8sLeaseLeaderElection} for Kubernetes-based leader
 *       election</li>
 *   <li>{@link S3SnapshotStore} for persisting catalog snapshots to
 *       S3-compatible storage (e.g. MinIO)</li>
 *   <li>{@link CatalogRegistrar} for registering the events catalog
 *       with its indices and refresh policy</li>
 * </ul>
 *
 * @author waabox(waabox[at]gmail[dot]com)
 */
@Configuration
public class AndersoniConfig {

  /** Creates a Kafka-based synchronization strategy for broadcasting
   * catalog refresh events across all nodes in the cluster.
   *
   * <p>Uses a dedicated topic and consumer group prefix to isolate
   * this application's sync traffic from other Andersoni instances.
   *
   * @param bootstrapServers the Kafka bootstrap servers connection
   *        string (e.g. "broker1:9092,broker2:9092"), never null
   *
   * @return the configured Kafka sync strategy, never null
   */
  @Bean
  public KafkaSyncStrategy kafkaSyncStrategy(
      @Value("${kafka.bootstrap-servers}") final String bootstrapServers) {

    final KafkaSyncConfig config = KafkaSyncConfig.create(
        bootstrapServers, "andersoni-events", "andersoni-example-");

    return new KafkaSyncStrategy(config);
  }

  /** Creates a Kubernetes Lease-based leader election strategy.
   *
   * <p>Only the leader node performs scheduled catalog refreshes, avoiding
   * redundant database queries across replicas. The lease is renewed every
   * 15 seconds and expires after 30 seconds of inactivity.
   *
   * @param nodeId the unique identifier for this node, typically the
   *        pod hostname, never null
   * @param namespace the Kubernetes namespace where the Lease resource
   *        is managed, never null
   *
   * @return the configured Kubernetes leader election strategy, never null
   */
  @Bean
  public K8sLeaseLeaderElection k8sLeaseLeaderElection(
      @Value("${andersoni.node-id}") final String nodeId,
      @Value("${k8s.lease.namespace:andersoni-example}")
          final String namespace) {

    final K8sLeaseConfig config = K8sLeaseConfig.create(
        "andersoni-example-leader",
        namespace,
        nodeId,
        Duration.ofSeconds(15),
        Duration.ofSeconds(30));

    return new K8sLeaseLeaderElection(config);
  }

  /** Creates an S3-backed snapshot store for persisting catalog data.
   *
   * <p>Uses an S3-compatible endpoint (such as MinIO for local development)
   * with static credentials and path-style access. Snapshots are stored
   * in the configured bucket under the default {@code andersoni/} prefix.
   *
   * @param endpoint the S3-compatible endpoint URL (e.g.
   *        "http://localhost:9000"), never null
   * @param accessKey the access key for authentication, never null
   * @param secretKey the secret key for authentication, never null
   * @param bucket the S3 bucket where snapshots are stored, never null
   *
   * @return the configured S3 snapshot store, never null
   */
  @Bean
  public S3SnapshotStore s3SnapshotStore(
      @Value("${minio.endpoint}") final String endpoint,
      @Value("${minio.access-key}") final String accessKey,
      @Value("${minio.secret-key}") final String secretKey,
      @Value("${minio.bucket}") final String bucket) {

    final S3Client client = S3Client.builder()
        .endpointOverride(URI.create(endpoint))
        .forcePathStyle(true)
        .region(Region.US_EAST_1)
        .credentialsProvider(StaticCredentialsProvider.create(
            AwsBasicCredentials.create(accessKey, secretKey)))
        .build();

    final S3SnapshotConfig config = S3SnapshotConfig.builder()
        .bucket(bucket)
        .region(Region.US_EAST_1)
        .s3Client(client)
        .build();

    return new S3SnapshotStore(config);
  }

  /** Creates a catalog registrar that registers the events catalog with
   * the Andersoni instance.
   *
   * <p>The events catalog is configured with:
   * <ul>
   *   <li>Three indices: by-sport, by-venue, and by-status for fast
   *       lookups on the most common query dimensions</li>
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
          .serializer(new EventSnapshotSerializer())
          .refreshEvery(Duration.ofMinutes(5))
          .build();

      andersoni.register(catalog);
    };
  }
}
