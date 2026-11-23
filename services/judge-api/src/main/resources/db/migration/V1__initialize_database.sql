-- Establish Flyway history without introducing domain tables in Stage 1.
-- Domain schema changes belong in subsequent versioned migrations.
SELECT 1;
