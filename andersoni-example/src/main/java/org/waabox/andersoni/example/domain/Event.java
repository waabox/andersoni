package org.waabox.andersoni.example.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDateTime;
import java.util.Objects;

/** A sports event loaded into the Andersoni cache.
 *
 * <p>This entity represents a scheduled or live sports event, including
 * details such as the competing teams, venue, sport type and current status.
 *
 * @author waabox(waabox[at]gmail[dot]com)
 */
@Entity
@Table(name = "events")
public class Event {

  @Id
  private String id;

  @Column(nullable = false)
  private String name;

  @Column(nullable = false)
  private String sport;

  @Column(nullable = false)
  private String venue;

  @Column(name = "home_team", nullable = false)
  private String homeTeam;

  @Column(name = "away_team", nullable = false)
  private String awayTeam;

  @Column(nullable = false)
  private String status;

  @Column(name = "start_time", nullable = false)
  private LocalDateTime startTime;

  /** JPA requires a no-arg constructor. */
  Event() {
  }

  /** Creates a new Event.
   *
   * @param theId the unique identifier, never null
   * @param theName the event display name, never null
   * @param theSport the sport type (e.g. FOOTBALL, BASKETBALL), never null
   * @param theVenue the venue where the event takes place, never null
   * @param theHomeTeam the home team name, never null
   * @param theAwayTeam the away team name, never null
   * @param theStatus the current event status (e.g. SCHEDULED, LIVE),
   *   never null
   * @param theStartTime the scheduled start time, never null
   */
  public Event(final String theId, final String theName,
      final String theSport, final String theVenue,
      final String theHomeTeam, final String theAwayTeam,
      final String theStatus, final LocalDateTime theStartTime) {
    id = Objects.requireNonNull(theId, "id cannot be null");
    name = Objects.requireNonNull(theName, "name cannot be null");
    sport = Objects.requireNonNull(theSport, "sport cannot be null");
    venue = Objects.requireNonNull(theVenue, "venue cannot be null");
    homeTeam = Objects.requireNonNull(theHomeTeam, "homeTeam cannot be null");
    awayTeam = Objects.requireNonNull(theAwayTeam, "awayTeam cannot be null");
    status = Objects.requireNonNull(theStatus, "status cannot be null");
    startTime = Objects.requireNonNull(theStartTime,
        "startTime cannot be null");
  }

  /** Returns the unique identifier for this event.
   *
   * @return the event id, never null
   */
  public String getId() {
    return id;
  }

  /** Returns the display name of this event.
   *
   * @return the event name, never null
   */
  public String getName() {
    return name;
  }

  /** Returns the sport type for this event.
   *
   * @return the sport type (e.g. FOOTBALL, BASKETBALL), never null
   */
  public String getSport() {
    return sport;
  }

  /** Returns the venue where this event takes place.
   *
   * @return the venue name, never null
   */
  public String getVenue() {
    return venue;
  }

  /** Returns the home team name.
   *
   * @return the home team, never null
   */
  public String getHomeTeam() {
    return homeTeam;
  }

  /** Returns the away team name.
   *
   * @return the away team, never null
   */
  public String getAwayTeam() {
    return awayTeam;
  }

  /** Returns the current status of this event.
   *
   * @return the event status (e.g. SCHEDULED, LIVE), never null
   */
  public String getStatus() {
    return status;
  }

  /** Returns the scheduled start time.
   *
   * @return the start time, never null
   */
  public LocalDateTime getStartTime() {
    return startTime;
  }

  /** {@inheritDoc} */
  @Override
  public String toString() {
    return id;
  }
}
