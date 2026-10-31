package com.tejas.contentreview.reviewservice.review;

import com.tejas.contentreview.contracts.events.EventCodec;
import com.tejas.contentreview.contracts.events.ReviewRequestedPayload;
import com.tejas.contentreview.contracts.events.Topics;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Consumes review requests. The offset is committed only after {@link ReviewService#handle} committed the decision,
 * receipt and outbox event; any failure leaves the record to be retried and eventually dead-lettered.
 */
@Component
public class ReviewRequestListener {

    private final ReviewService reviewService;

    public ReviewRequestListener(ReviewService reviewService) {
        this.reviewService = reviewService;
    }

    @KafkaListener(topics = Topics.REVIEW_REQUESTED, groupId = Topics.GROUP_REVIEW_WORKERS)
    public void onReviewRequested(ConsumerRecord<String, String> record) {
        reviewService.handle(EventCodec.fromJson(record.value(), ReviewRequestedPayload.class));
    }
}
