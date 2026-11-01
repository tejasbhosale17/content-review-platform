package com.tejas.contentreview.caseservice;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tejas.contentreview.caseservice.content.ContentLookup;
import com.tejas.contentreview.caseservice.content.GrpcContentLookup;
import com.tejas.contentreview.contracts.grpc.ContentMetadataGrpc;
import com.tejas.contentreview.contracts.grpc.GetContentRequest;
import com.tejas.contentreview.contracts.grpc.GetContentResponse;
import io.grpc.Server;
import io.grpc.Status;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import io.grpc.stub.StreamObserver;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Real client over a real gRPC socket against a scripted server. */
class GrpcContentLookupTest {

    Server server;
    GrpcContentLookup lookup;

    @BeforeEach
    void start() throws Exception {
        server = NettyServerBuilder.forPort(0).addService(new ContentMetadataGrpc.ContentMetadataImplBase() {
            @Override
            public void getContent(GetContentRequest request, StreamObserver<GetContentResponse> out) {
                switch (request.getContentId()) {
                    case "known" -> {
                        out.onNext(GetContentResponse.newBuilder().setContentId("known").setCreatorId("c-1").build());
                        out.onCompleted();
                    }
                    case "slow" -> {
                        // never answers within the deadline
                    }
                    case "broken" -> out.onError(Status.UNAVAILABLE.asRuntimeException());
                    default -> out.onError(Status.NOT_FOUND.asRuntimeException());
                }
            }
        }).build().start();
        lookup = new GrpcContentLookup("localhost:" + server.getPort(), Duration.ofMillis(400));
    }

    @AfterEach
    void stop() throws Exception {
        lookup.close();
        server.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
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
    void deadlineIsReportedAsUnavailable() {
        assertThatThrownBy(() -> lookup.find("slow")).isInstanceOf(ContentLookup.ContentUnavailableException.class);
    }

    @Test
    void serverErrorIsReportedAsUnavailable() {
        assertThatThrownBy(() -> lookup.find("broken")).isInstanceOf(ContentLookup.ContentUnavailableException.class);
    }

    @Test
    void unreachableServerIsReportedAsUnavailable() throws Exception {
        server.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
        assertThatThrownBy(() -> lookup.find("known")).isInstanceOf(ContentLookup.ContentUnavailableException.class);
    }
}
