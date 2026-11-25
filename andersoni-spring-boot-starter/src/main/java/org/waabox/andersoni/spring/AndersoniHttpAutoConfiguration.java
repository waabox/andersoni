package org.waabox.andersoni.spring;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.waabox.andersoni.Andersoni;
import org.waabox.andersoni.metrics.http.AndersoniStatusServlet;

/**
 * Registers Andersoni's status servlet on the application's servlet
 * container, at {@code GET /andersoni/status}.
 *
 * <p>Active when {@code andersoni-metrics-http} is on the classpath, the
 * application is a servlet web application and an {@link Andersoni} bean
 * exists. Disable it with {@code andersoni.http.enabled=false}.
 *
 * <p>The servlet is registered beside Spring MVC's
 * {@code DispatcherServlet}, not behind it: the path is relative to
 * {@code server.servlet.context-path} but not to
 * {@code spring.mvc.servlet.path}, and servlet filters such as Spring
 * Security's still apply.
 */
@AutoConfiguration(after = AndersoniAutoConfiguration.class)
@ConditionalOnClass(name = {
    "org.waabox.andersoni.metrics.http.AndersoniStatusServlet",
    "jakarta.servlet.Servlet"})
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnProperty(prefix = "andersoni.http", name = "enabled",
    havingValue = "true", matchIfMissing = true)
@ConditionalOnBean(Andersoni.class)
public class AndersoniHttpAutoConfiguration {

  /** The path the status document is served at. */
  static final String STATUS_PATH = "/andersoni/status";

  /**
   * Registers the servlet serving {@code GET /andersoni/status}.
   *
   * @param andersoni the node to report on, never null
   *
   * @return the servlet registration, never null
   */
  @Bean
  @ConditionalOnMissingBean(name = "andersoniStatusServlet")
  public ServletRegistrationBean<AndersoniStatusServlet> andersoniStatusServlet(
      final Andersoni andersoni) {
    final ServletRegistrationBean<AndersoniStatusServlet> registration =
        new ServletRegistrationBean<>(new AndersoniStatusServlet(andersoni),
            STATUS_PATH);
    registration.setName("andersoniStatusServlet");
    return registration;
  }
}
