CREATE DATABASE keycloak;

CREATE TABLE IF NOT EXISTS skylanders (
    id SERIAL PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    element VARCHAR(50),
    series VARCHAR(50),
    price DECIMAL(10, 2),
    stock INT DEFAULT 1,
    image_url TEXT,
    description TEXT
);
\c skylanders_shop;
