# Getting Started

## Requirements

- Java 21+
- Maven 3.9+ (or Gradle equivalent)

## Add the Dependency

```xml
<dependency>
  <groupId>io.github.waabox</groupId>
  <artifactId>andersoni-core</artifactId>
  <version>1.1.2</version>
</dependency>
```

## Minimal Example (Standalone)

No Spring Boot required. This example loads a list of events into a catalog with two indices and searches by sport and venue.

```java
import org.waabox.andersoni.Andersoni;
import org.waabox.andersoni.Catalog;

// 1. Define your domain model
record Venue(String name, String city) {}
record Event(String name, String sport, Venue venue) {}

// 2. Build a Catalog with indices
Catalog<Event> catalog = Catalog.of(Event.class)
    .named("events")
    .data(List.of(
        new Event("Final", "Soccer", new Venue("Wembley", "London")),
        new Event("Semifinal", "Soccer", new Venue("Camp Nou", "Barcelona")),
        new Event("Game 7", "Basketball", new Venue("MSG", "New York"))
    ))
    .index("by-sport").by(Event::sport)
    .index("by-venue").by(Event::venue, Venue::name)
    .build();

// 3. Create the engine and start
Andersoni andersoni = Andersoni.builder()
    .nodeId("local-node")
    .build();

andersoni.register(catalog);
andersoni.start();

// 4. Search
List<?> soccer = andersoni.search("events", "by-sport", "Soccer");
// → [Final, Semifinal]

List<?> msg = andersoni.search("events", "by-venue", "MSG");
// → [Game 7]

// 5. Shutdown
andersoni.stop();
```

## With a DataLoader

For dynamic data that comes from a database or external service, use a `DataLoader` instead of `.data()`:

```java
Catalog<Event> catalog = Catalog.of(Event.class)
    .named("events")
    .loadWith(() -> eventRepository.findAll())   // DataLoader
    .index("by-sport").by(Event::sport)
    .index("by-venue").by(Event::venue, Venue::name)
    .build();
```

When `andersoni.start()` is called, the DataLoader is invoked to bootstrap the catalog. Later, you can refresh the data:

```java
// Refresh from the DataLoader and broadcast the change to other nodes
andersoni.refreshAndSync("events");
```

## With Scheduled Refresh

To automatically refresh a catalog on a fixed interval:

```java
Catalog<Event> catalog = Catalog.of(Event.class)
    .named("events")
    .loadWith(() -> eventRepository.findAll())
    .refreshEvery(Duration.ofMinutes(5))          // auto-refresh every 5 min
    .index("by-sport").by(Event::sport)
    .build();
```

Scheduled refreshes are **leader-only** — only the leader node executes the DataLoader and broadcasts the change.

## Next Steps

- [[Core Concepts]] — understand the architecture
- [[The Catalog DSL]] — full builder reference
- [[Spring Boot Integration]] — auto-configuration with Spring Boot
