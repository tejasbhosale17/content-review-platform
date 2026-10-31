package com.tejas.contentreview.contentservice.reliability;

import com.tejas.contentreview.contracts.events.Topics;
import java.util.Map;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Operational endpoint; restricted to administrators once access control lands. */
@RestController
@RequestMapping("/internal/dlt")
public class DltController {

    private final DltReplayService replayService;

    public DltController(DltReplayService replayService) {
        this.replayService = replayService;
    }

    @PostMapping("/replay")
    public Map<String, Object> replay(@RequestParam(defaultValue = "100") int max) {
        String dlt = Topics.deadLetter(Topics.REVIEW_COMPLETED, Topics.GROUP_CONTENT_PROJECTION);
        return Map.of("deadLetterTopic", dlt, "replayed", replayService.replay(dlt, Topics.GROUP_CONTENT_PROJECTION, Math.min(max, 1000)));
    }
}
