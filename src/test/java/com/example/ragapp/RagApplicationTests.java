package com.example.ragapp;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Smoke test: the whole application starts, Flyway migrations run, and Spring AI is wired.
 * Needs the Docker Compose stack to be running:  docker compose up -d
 */
@SpringBootTest
class RagApplicationTests {

    @Test
    void contextLoads() {
    }
}
