-- Initialize PostgreSQL database with ~10K sports events for Andersoni example

CREATE TABLE IF NOT EXISTS events (
    id VARCHAR(36) PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    sport VARCHAR(50) NOT NULL,
    venue VARCHAR(255) NOT NULL,
    home_team VARCHAR(255) NOT NULL,
    away_team VARCHAR(255) NOT NULL,
    status VARCHAR(20) NOT NULL,
    start_time TIMESTAMP NOT NULL
);

-- Insert ~10K rows using generate_series with LATERAL for arrays
INSERT INTO events (id, name, sport, venue, home_team, away_team, status, start_time)
SELECT
    gen_random_uuid()::text,
    sport || ': ' || home_team || ' vs ' || away_team,
    sport,
    venue,
    home_team,
    away_team,
    status,
    '2026-01-01'::timestamp + (i || ' hours')::interval
FROM generate_series(0, 9999) AS i
CROSS JOIN LATERAL (
    SELECT
        (ARRAY['FOOTBALL', 'BASKETBALL', 'TENNIS', 'BASEBALL', 'HOCKEY', 'RUGBY'])[((i % 6) + 1)] AS sport,
        (ARRAY['Wembley', 'MSG', 'Camp Nou', 'San Siro', 'Maracana', 'Old Trafford', 'Anfield',
                'Bernabeu', 'Allianz Arena', 'Staples Center', 'Emirates', 'Etihad', 'Signal Iduna',
                'Johan Cruyff', 'Olimpico', 'Azteca', 'Centenario', 'Monumental', 'Bombonera', 'MCG'])[((i % 20) + 1)] AS venue,
        (ARRAY['Arsenal', 'Barcelona', 'Real Madrid', 'Bayern Munich', 'Liverpool', 'Manchester City',
                'Juventus', 'PSG', 'Inter Milan', 'Dortmund', 'Chelsea', 'Tottenham', 'AC Milan',
                'Ajax', 'Benfica', 'Porto', 'Celtic', 'Rangers', 'Boca Juniors', 'River Plate'])[((i % 20) + 1)] AS home_team,
        (ARRAY['Atletico Madrid', 'Sevilla', 'Valencia', 'Napoli', 'Roma', 'Lazio', 'Fiorentina',
                'Lyon', 'Marseille', 'Monaco', 'Sporting', 'Braga', 'Feyenoord', 'PSV', 'Anderlecht',
                'Club Brugge', 'Galatasaray', 'Fenerbahce', 'Flamengo', 'Santos'])[((i % 20) + 1)] AS away_team,
        (ARRAY['SCHEDULED', 'LIVE', 'FINISHED', 'CANCELLED'])[((i % 4) + 1)] AS status
) AS t;

-- Create index on start_time for efficient querying
CREATE INDEX IF NOT EXISTS idx_events_start_time ON events(start_time);

-- Create index on status for filtering
CREATE INDEX IF NOT EXISTS idx_events_status ON events(status);

-- Create index on sport for filtering
CREATE INDEX IF NOT EXISTS idx_events_sport ON events(sport);
