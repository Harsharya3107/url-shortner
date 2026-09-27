-- Runs once, when the Postgres volume is first created. Tests use their own database so
-- they can truncate tables freely without touching dev data.
CREATE DATABASE urlshortener_test OWNER urlshortener;
