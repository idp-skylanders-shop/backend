-- PostgreSQL init script — runs once on first container start
-- Creates the keycloak database and the skylanders_shop schema

CREATE DATABASE keycloak;

-- Switch context: PostgreSQL init runs in POSTGRES_DB (skylanders_shop)
-- The tables below are created in skylanders_shop

CREATE TABLE IF NOT EXISTS skylanders (
    id          SERIAL PRIMARY KEY,
    name        VARCHAR(255) NOT NULL,
    element     VARCHAR(50),
    series      VARCHAR(50),
    price       DECIMAL(10, 2),
    stock       INT DEFAULT 1,
    image_url   TEXT,
    description TEXT,
    owner       VARCHAR(255) DEFAULT 'admin'
);

CREATE TABLE IF NOT EXISTS cart_items (
    id          SERIAL PRIMARY KEY,
    user_id     VARCHAR(255) NOT NULL,
    product_id  INT NOT NULL REFERENCES skylanders(id) ON DELETE CASCADE
);

\c skylanders_shop;
