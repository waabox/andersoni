package org.waabox.andersoni.spring;

import org.waabox.andersoni.Andersoni;

/**
 * A callback interface for registering {@link org.waabox.andersoni.Catalog}
 * instances with an {@link Andersoni} instance during Spring Boot
 * auto-configuration.
 *
 * <p>Implement this interface as a Spring bean to register one or more
 * catalogs. All discovered {@code CatalogRegistrar} beans are invoked
 * during the Andersoni bean creation, before the lifecycle starts.
 *
 * <p>Example usage:
 * <pre>{@code
 * @Bean
 * CatalogRegistrar eventsCatalogRegistrar() {
 *     return andersoni -> {
 *         Catalog<Event> catalog = Catalog.of(Event.class)
 *             .named("events")
 *             .loadWith(() -> eventRepository.findAll())
 *             .index("by-sport").by(Event::getSport, Sport::getName)
 *             .build();
 *         andersoni.register(catalog);
 *     };
 * }
 * }</pre>
 *
 * @author waabox(waabox[at]gmail[dot]com)
 */
@FunctionalInterface
public interface CatalogRegistrar {

  /**
   * Registers one or more catalogs with the given Andersoni instance.
   *
   * @param andersoni the Andersoni instance to register catalogs with,
   *                  never null
   */
  void register(Andersoni andersoni);
}
