package com.tejas.contentreview.reviewservice.content;

import com.tejas.contentreview.contracts.grpc.ContentMetadataGrpc;
import com.tejas.contentreview.contracts.grpc.GetContentRequest;
import com.tejas.contentreview.contracts.grpc.GetContentResponse;
import io.grpc.ManagedChannel;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** gRPC client with a finite deadline on every call. Status codes map to distinct outcomes. */
@Component
public class GrpcContentMetadataClient implements ContentMetadataClient {

    private final ManagedChannel channel;
    private final ContentMetadataGrpc.ContentMetadataBlockingStub stub;
    private final Duration deadline;

    public GrpcContentMetadataClient(@Value("${content-service.grpc.target}") String target,
                                     @Value("${content-service.grpc.deadline}") Duration deadline) {
        this.deadline = deadline;
        this.channel = NettyChannelBuilder.forTarget(target).usePlaintext().build();
        this.stub = ContentMetadataGrpc.newBlockingStub(channel);
    }

    @Override
    public Optional<ContentMetadata> get(String contentId) {
        try {
            GetContentResponse r = stub.withDeadlineAfter(deadline.toMillis(), TimeUnit.MILLISECONDS)
                    .getContent(GetContentRequest.newBuilder().setContentId(contentId).build());
            return Optional.of(new ContentMetadata(r.getContentId(), r.getCreatorId(), r.getLicenseValid(),
                    r.getLicenseNote().isEmpty() ? null : r.getLicenseNote()));
        } catch (StatusRuntimeException e) {
            Status.Code code = e.getStatus().getCode();
            return switch (code) {
                case NOT_FOUND -> Optional.empty();
                case INVALID_ARGUMENT -> throw new InvalidContentRequestException(
                        "content-service rejected request: " + e.getStatus().getDescription(), e);
                case DEADLINE_EXCEEDED -> throw new ContentDeadlineExceededException(
                        "content-service lookup exceeded " + deadline, e);
                default -> throw new ContentUnavailableException("content-service lookup failed: " + code, e);
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
