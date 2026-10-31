package com.tejas.contentreview.contentservice.grpc;

import com.tejas.contentreview.contentservice.content.Content;
import com.tejas.contentreview.contentservice.content.ContentRepository;
import com.tejas.contentreview.contracts.grpc.ContentMetadataGrpc;
import com.tejas.contentreview.contracts.grpc.GetContentRequest;
import com.tejas.contentreview.contracts.grpc.GetContentResponse;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** Read-only view of content for other services. NOT_FOUND and INVALID_ARGUMENT are distinct from server failures. */
@Component
public class ContentMetadataGrpcService extends ContentMetadataGrpc.ContentMetadataImplBase {

    private static final Logger log = LoggerFactory.getLogger(ContentMetadataGrpcService.class);
    private static final int MAX_ID_LENGTH = 36;

    private final ContentRepository repository;

    public ContentMetadataGrpcService(ContentRepository repository) {
        this.repository = repository;
    }

    @Override
    public void getContent(GetContentRequest request, StreamObserver<GetContentResponse> observer) {
        String id = request.getContentId();
        if (id.isBlank() || id.length() > MAX_ID_LENGTH) {
            observer.onError(Status.INVALID_ARGUMENT.withDescription("content_id must be 1-" + MAX_ID_LENGTH + " characters").asRuntimeException());
            return;
        }
        try {
            var found = repository.findById(id);
            if (found.isEmpty()) {
                observer.onError(Status.NOT_FOUND.withDescription("Content " + id + " not found").asRuntimeException());
                return;
            }
            Content c = found.get();
            observer.onNext(GetContentResponse.newBuilder()
                    .setContentId(c.getContentId())
                    .setCreatorId(c.getCreatorId())
                    .setTitle(c.getTitle())
                    .setLicenseValid(c.isLicenseValid())
                    .setLicenseNote(c.getLicenseNote() == null ? "" : c.getLicenseNote())
                    .setVisibility(c.getVisibility().name())
                    .setAppliedReviewRound(c.getAppliedReviewRound())
                    .build());
            observer.onCompleted();
        } catch (RuntimeException e) {
            log.error("GetContent failed contentId={}", id, e);
            observer.onError(Status.UNAVAILABLE.withDescription("content store unavailable").asRuntimeException());
        }
    }
}
