package com.tejas.contentreview.caseservice;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import com.tejas.contentreview.caseservice.content.ContentLookup;
import com.tejas.contentreview.caseservice.content.RestContentLookup;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class RestContentLookupTest {

    HttpServer server;
    RestContentLookup lookup;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/api/contents/known", ex -> reply(ex, 200,
                "{\"contentId\":\"known\",\"creatorId\":\"c-1\",\"title\":\"t\",\"extra\":1}"));
        server.createContext("/api/contents/slow", ex -> {
            try {
                Thread.sleep(1500);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            reply(ex, 200, "{}");
        });
        server.createContext("/api/contents/", ex -> reply(ex, 404, "{}"));
        server.start();
        lookup = lookupFor(server.getAddress().getPort());
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private static RestContentLookup lookupFor(int port) {
        return new RestContentLookup("http://127.0.0.1:" + port, Duration.ofMillis(500), Duration.ofMillis(300));
    }

    private static void reply(com.sun.net.httpserver.HttpExchange ex, int code, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(code, bytes.length);
        ex.getResponseBody().write(bytes);
        ex.close();
    }

    @Test
    void returnsContentWhenFound() {
        assertThat(lookup.find("known")).hasValueSatisfying(c -> assertThat(c.creatorId()).isEqualTo("c-1"));
    }

    @Test
    void returnsEmptyWhenMissing() {
        assertThat(lookup.find("nope")).isEmpty();
    }

    @Test
    void readTimeoutIsReportedAsUnavailable() {
        assertThatThrownBy(() -> lookup.find("slow")).isInstanceOf(ContentLookup.ContentUnavailableException.class);
    }

    @Test
    void connectionRefusedIsReportedAsUnavailable() throws IOException {
        int freePort;
        try (ServerSocket s = new ServerSocket(0)) {
            freePort = s.getLocalPort();
        }
        assertThatThrownBy(() -> lookupFor(freePort).find("known"))
                .isInstanceOf(ContentLookup.ContentUnavailableException.class);
    }
}
