package com.tejas.contentreview.caseservice.content;

import com.tejas.contentreview.contracts.grpc.ContentMetadataGrpc;
import com.tejas.contentreview.contracts.grpc.GetContentRequest;
import io.grpc.ManagedChannel;
import io.grpc.StatusRuntimeException;
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** gRPC lookup with a finite deadline on every call. */
@Component
public class GrpcContentLookup implements ContentLookup {

    private final ManagedChannel channel;
    private final ContentMetadataGrpc.ContentMetadataBlockingStub stub;
    private final Duration deadline;

    public GrpcContentLookup(@Value("${content-service.grpc.target}") String target,
                             @Value("${content-service.grpc.deadline}") Duration deadline) {
        this.deadline = deadline;
        this.channel = NettyChannelBuilder.forTarget(target).usePlaintext().build();
        this.stub = ContentMetadataGrpc.newBlockingStub(channel);
    }

    @Override
    public Optional<ContentInfo> find(String contentId) {
        try {
            var r = stub.withDeadlineAfter(deadline.toMillis(), TimeUnit.MILLISECONDS)
                    .getContent(GetContentRequest.newBuilder().setContentId(contentId).build());
            return Optional.of(new ContentInfo(r.getContentId(), r.getCreatorId()));
        } catch (StatusRuntimeException e) {
            return switch (e.getStatus().getCode()) {
                // An ID the server rejects as malformed cannot refer to existing content.
                case NOT_FOUND, INVALID_ARGUMENT -> Optional.empty();
                default -> throw new ContentUnavailableException(
                        "content-service lookup failed: " + e.getStatus().getCode(), e);
            };
        }
    }

    @PreDestroy
    public void close() throws InterruptedException {
        channel.shutdown();
        if (!channel.awaitTermination(3, TimeUnit.SECONDS)) {
            channel.shutdownNow();
        }
    }
}
