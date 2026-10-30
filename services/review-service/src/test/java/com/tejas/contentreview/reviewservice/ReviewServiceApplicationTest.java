package com.tejas.contentreview.reviewservice;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ReviewServiceApplicationTest {

    @Autowired
    TestRestTemplate rest;

    @Test
    void healthEndpointIsUp() {
        assertThat(rest.getForObject("/actuator/health", String.class)).contains("\"status\":\"UP\"");
    }
}
