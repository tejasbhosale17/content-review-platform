package com.tejas.contentreview.caseservice.content;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record ContentInfo(String contentId, String creatorId) {
}
