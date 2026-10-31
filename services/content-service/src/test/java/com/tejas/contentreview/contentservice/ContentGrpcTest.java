package com.tejas.contentreview.contentservice;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tejas.contentreview.contentservice.content.Content;
import com.tejas.contentreview.contentservice.content.ContentRepository;
import com.tejas.contentreview.contentservice.grpc.GrpcServerLifecycle;
import com.tejas.contentreview.contracts.grpc.ContentMetadataGrpc;
import com.tejas.contentreview.contracts.grpc.GetContentRequest;
import io.grpc.ManagedChannel;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.health.v1.HealthCheckRequest;
import io.grpc.health.v1.HealthCheckResponse;
import io.grpc.health.v1.HealthGrpc;
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/** Calls the real gRPC server over a real socket, backed by the MySQL container. */
@SpringBootTest
@Import(InfraTestConfig.class)
class ContentGrpcTest {

    @Autowired
    GrpcServerLifecycle grpcServer;
    @Autowired
    ContentRepository repository;

    ManagedChannel channel;
    ContentMetadataGrpc.ContentMetadataBlockingStub stub;

    @BeforeEach
    void connect() {
        channel = NettyChannelBuilder.forAddress("localhost", grpcServer.getPort()).usePlaintext().build();
        stub = ContentMetadataGrpc.newBlockingStub(channel).withDeadlineAfter(5, TimeUnit.SECONDS);
    }

    @AfterEach
    void disconnect() throws InterruptedException {
        channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
    }

    @Test
    void returnsStoredContent() {
        String id = UUID.randomUUID().toString();
        repository.save(new Content(id, "creator-9", "Clip", false, "expired license", Instant.now()));

        var response = stub.getContent(GetContentRequest.newBuilder().setContentId(id).build());

        assertThat(response.getContentId()).isEqualTo(id);
        assertThat(response.getCreatorId()).isEqualTo("creator-9");
        assertThat(response.getTitle()).isEqualTo("Clip");
        assertThat(response.getLicenseValid()).isFalse();
        assertThat(response.getLicenseNote()).isEqualTo("expired license");
        assertThat(response.getVisibility()).isEqualTo("VISIBLE");
        assertThat(response.getAppliedReviewRound()).isZero();
    }

    @Test
    void unknownContentIsNotFound() {
        assertThatThrownBy(() -> stub.getContent(GetContentRequest.newBuilder().setContentId(UUID.randomUUID().toString()).build()))
                .isInstanceOfSatisfying(StatusRuntimeException.class,
                        e -> assertThat(e.getStatus().getCode()).isEqualTo(Status.Code.NOT_FOUND));
    }

    @Test
    void blankOrOversizedIdIsInvalidArgument() {
        for (String bad : new String[] {"", "   ", "x".repeat(37)}) {
            assertThatThrownBy(() -> stub.getContent(GetContentRequest.newBuilder().setContentId(bad).build()))
                    .isInstanceOfSatisfying(StatusRuntimeException.class,
                            e -> assertThat(e.getStatus().getCode()).isEqualTo(Status.Code.INVALID_ARGUMENT));
        }
    }

    @Test
    void standardHealthServiceReportsServing() {
        var health = HealthGrpc.newBlockingStub(channel).withDeadlineAfter(5, TimeUnit.SECONDS)
                .check(HealthCheckRequest.getDefaultInstance());
        assertThat(health.getStatus()).isEqualTo(HealthCheckResponse.ServingStatus.SERVING);
    }
}
