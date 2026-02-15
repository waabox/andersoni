package org.waabox.andersoni.admin;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** Entry point for the Andersoni Admin Console.
 *
 * <p>Standalone Spring Boot application that provides a dashboard for
 * monitoring Andersoni instances running in a Kubernetes cluster.
 *
 * @author waabox(waabox[at]gmail[dot]com)
 */
@SpringBootApplication
public class AdminApplication {

  /** Launches the admin console.
   *
   * @param args command-line arguments, never null.
   */
  public static void main(final String[] args) {
    SpringApplication.run(AdminApplication.class, args);
  }
}
