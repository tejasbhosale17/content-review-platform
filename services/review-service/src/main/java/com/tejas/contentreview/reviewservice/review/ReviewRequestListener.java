package com.tejas.contentreview.reviewservice.review;

import com.tejas.contentreview.contracts.events.EventCodec;
import com.tejas.contentreview.contracts.events.ReviewRequestedPayload;
import com.tejas.contentreview.contracts.events.Topics;
import com.tejas.contentreview.reviewservice.logging.EventLogContext;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
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
    private final MeterRegistry meters;

    public ReviewRequestListener(ReviewService reviewService, MeterRegistry meters) {
        this.reviewService = reviewService;
        this.meters = meters;
    }

    @KafkaListener(topics = Topics.REVIEW_REQUESTED, groupId = Topics.GROUP_REVIEW_WORKERS)
    public void onReviewRequested(ConsumerRecord<String, String> record) {
        var envelope = EventCodec.fromJson(record.value(), ReviewRequestedPayload.class);
        Timer.Sample sample = Timer.start(meters);
        try (var ignored = EventLogContext.open(envelope)) {
            reviewService.handle(envelope);
        } finally {
            sample.stop(meters.timer("kafka.consumer.processing", "group", Topics.GROUP_REVIEW_WORKERS));
        }
    }
}
