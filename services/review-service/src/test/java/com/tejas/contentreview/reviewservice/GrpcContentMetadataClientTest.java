package com.tejas.contentreview.reviewservice;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tejas.contentreview.contracts.grpc.ContentMetadataGrpc;
import com.tejas.contentreview.contracts.grpc.GetContentRequest;
import com.tejas.contentreview.contracts.grpc.GetContentResponse;
import com.tejas.contentreview.reviewservice.content.ContentMetadataClient.ContentDeadlineExceededException;
import com.tejas.contentreview.reviewservice.content.ContentMetadataClient.ContentUnavailableException;
import com.tejas.contentreview.reviewservice.content.ContentMetadataClient.InvalidContentRequestException;
import com.tejas.contentreview.reviewservice.content.GrpcContentMetadataClient;
import io.grpc.Server;
import io.grpc.Status;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import io.grpc.stub.StreamObserver;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Exercises the real client over a real gRPC socket against a scripted server (no Spring context, no database). */
class GrpcContentMetadataClientTest {

    private Server server;
    private GrpcContentMetadataClient client;

    @BeforeEach
    void start() throws Exception {
        server = NettyServerBuilder.forPort(0).addService(new ScriptedService()).build().start();
        client = new GrpcContentMetadataClient("localhost:" + server.getPort(), Duration.ofMillis(500));
    }

    @AfterEach
    void stop() throws Exception {
        client.close();
        server.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
    }

    @Test
    void mapsResponseFields() {
        var content = client.get("ok-1").orElseThrow();
        assertThat(content.contentId()).isEqualTo("ok-1");
        assertThat(content.creatorId()).isEqualTo("creator-1");
        assertThat(content.licenseValid()).isFalse();
        assertThat(content.licenseNote()).isNull();
    }

    @Test
    void missingContentIsEmpty() {
        assertThat(client.get("missing")).isEmpty();
    }

    @Test
    void invalidRequestIsNotRetryable() {
        assertThatThrownBy(() -> client.get("invalid")).isInstanceOf(InvalidContentRequestException.class);
    }

    @Test
    void serverErrorIsTemporaryUnavailability() {
        assertThatThrownBy(() -> client.get("broken"))
                .isInstanceOf(ContentUnavailableException.class)
                .isNotInstanceOf(ContentDeadlineExceededException.class);
    }

    @Test
    void slowServerHitsDeadline() {
        long start = System.nanoTime();
        assertThatThrownBy(() -> client.get("slow")).isInstanceOf(ContentDeadlineExceededException.class);
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(3));
    }

    @Test
    void unreachableServerIsTemporaryUnavailability() throws Exception {
        server.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
        assertThatThrownBy(() -> client.get("ok-1"))
                .isInstanceOf(ContentUnavailableException.class)
                .isNotInstanceOf(ContentDeadlineExceededException.class);
    }

    private static class ScriptedService extends ContentMetadataGrpc.ContentMetadataImplBase {
        @Override
        public void getContent(GetContentRequest request, StreamObserver<GetContentResponse> out) {
            switch (request.getContentId()) {
                case "missing" -> out.onError(Status.NOT_FOUND.asRuntimeException());
                case "invalid" -> out.onError(Status.INVALID_ARGUMENT.withDescription("bad id").asRuntimeException());
                case "broken" -> out.onError(Status.UNAVAILABLE.asRuntimeException());
                case "slow" -> {
                    // Never answers within the client's deadline.
                }
                default -> {
                    out.onNext(GetContentResponse.newBuilder().setContentId(request.getContentId())
                            .setCreatorId("creator-1").setLicenseValid(false).build());
                    out.onCompleted();
                }
            }
        }
    }
}
