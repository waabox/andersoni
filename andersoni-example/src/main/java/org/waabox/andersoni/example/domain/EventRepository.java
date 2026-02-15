package org.waabox.andersoni.example.domain;

import jakarta.persistence.EntityManager;

import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Objects;

/** Repository for retrieving {@link Event} entities from the database.
 *
 * <p>Uses {@link EntityManager} directly with JPQL queries instead of
 * Spring Data interfaces, following the project convention of concrete
 * repository classes.
 *
 * @author waabox(waabox[at]gmail[dot]com)
 */
@Repository
public class EventRepository {

  /** The JPA entity manager, never null. */
  private final EntityManager entityManager;

  /** Creates a new EventRepository.
   *
   * @param theEntityManager the JPA entity manager, never null
   */
  public EventRepository(final EntityManager theEntityManager) {
    entityManager = Objects.requireNonNull(theEntityManager,
        "entityManager cannot be null");
  }

  /** Retrieves all events from the database.
   *
   * @return the list of all events, never null
   */
  public List<Event> findAll() {
    return entityManager
        .createQuery("SELECT e FROM Event e", Event.class)
        .getResultList();
  }
}
