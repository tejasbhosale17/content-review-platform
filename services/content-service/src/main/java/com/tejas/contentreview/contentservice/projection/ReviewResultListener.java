package com.tejas.contentreview.contentservice.projection;

import com.tejas.contentreview.contracts.events.EventCodec;
import com.tejas.contentreview.contracts.events.ReviewCompletedPayload;
import com.tejas.contentreview.contracts.events.Topics;
import com.tejas.contentreview.contentservice.logging.EventLogContext;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
public class ReviewResultListener {

    private final ContentProjectionService projection;
    private final MeterRegistry meters;

    public ReviewResultListener(ContentProjectionService projection, MeterRegistry meters) {
        this.projection = projection;
        this.meters = meters;
    }

    @KafkaListener(topics = Topics.REVIEW_COMPLETED, groupId = Topics.GROUP_CONTENT_PROJECTION)
    public void onReviewCompleted(ConsumerRecord<String, String> record) {
        var envelope = EventCodec.fromJson(record.value(), ReviewCompletedPayload.class);
        Timer.Sample sample = Timer.start(meters);
        try (var ignored = EventLogContext.open(envelope)) {
            projection.apply(envelope);
        } finally {
            sample.stop(meters.timer("kafka.consumer.processing", "group", Topics.GROUP_CONTENT_PROJECTION));
        }
    }
}
