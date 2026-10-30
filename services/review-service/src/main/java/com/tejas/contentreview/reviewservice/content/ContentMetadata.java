package com.tejas.contentreview.reviewservice.content;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record ContentMetadata(String contentId, String creatorId, boolean licenseValid, String licenseNote) {
}
