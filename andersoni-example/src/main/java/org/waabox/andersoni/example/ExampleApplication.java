package org.waabox.andersoni.example;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** Spring Boot application entry point for the Andersoni example service.
 *
 * <p>This application demonstrates how to use the Andersoni in-memory cache
 * library with Spring Boot, including:
 * <ul>
 *   <li>Kafka-based cross-node cache synchronization</li>
 *   <li>Kubernetes Lease-based leader election</li>
 *   <li>S3-compatible snapshot persistence (MinIO)</li>
 *   <li>REST API for searching and managing the events catalog</li>
 * </ul>
 *
 * @author waabox(waabox[at]gmail[dot]com)
 */
@SpringBootApplication
public class ExampleApplication {

  /** Launches the Spring Boot application.
   *
   * @param args the command-line arguments
   */
  public static void main(final String[] args) {
    SpringApplication.run(ExampleApplication.class, args);
  }
}
