package org.waabox.andersoni.spring;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.waabox.andersoni.Andersoni;

/**
 * Mounts Andersoni's HTTP endpoints on the application's Spring MVC server.
 *
 * <p>Active when {@code andersoni-metrics-http} is on the classpath, the
 * application is a servlet web application and an {@link Andersoni} bean
 * exists. Disable it with {@code andersoni.http.enabled=false}.
 */
@AutoConfiguration(after = AndersoniAutoConfiguration.class)
@ConditionalOnClass(name = {
    "org.waabox.andersoni.metrics.http.AndersoniStatusJson",
    "org.springframework.web.servlet.DispatcherServlet"})
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnProperty(prefix = "andersoni.http", name = "enabled",
    havingValue = "true", matchIfMissing = true)
@ConditionalOnBean(Andersoni.class)
public class AndersoniHttpAutoConfiguration {

  /**
   * Creates the controller serving {@code GET /andersoni/status}.
   *
   * @param andersoni the node to report on, never null
   *
   * @return the controller, never null
   */
  @Bean
  @ConditionalOnMissingBean
  public AndersoniStatusController andersoniStatusController(
      final Andersoni andersoni) {
    return new AndersoniStatusController(andersoni);
  }
}
