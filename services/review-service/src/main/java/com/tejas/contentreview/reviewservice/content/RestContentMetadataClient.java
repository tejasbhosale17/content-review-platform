package com.tejas.contentreview.reviewservice.content;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.client.ClientHttpRequestFactories;
import org.springframework.boot.web.client.ClientHttpRequestFactorySettings;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

@Component
public class RestContentMetadataClient implements ContentMetadataClient {

    private final RestClient client;

    public RestContentMetadataClient(@Value("${content-service.base-url}") String baseUrl,
                                     @Value("${content-service.connect-timeout}") Duration connectTimeout,
                                     @Value("${content-service.read-timeout}") Duration readTimeout) {
        ClientHttpRequestFactory factory = ClientHttpRequestFactories.get(
                ClientHttpRequestFactorySettings.DEFAULTS.withConnectTimeout(connectTimeout).withReadTimeout(readTimeout));
        this.client = RestClient.builder().baseUrl(baseUrl).requestFactory(factory).build();
    }

    @Override
    public Optional<ContentMetadata> get(String contentId) {
        try {
            String encoded = URLEncoder.encode(contentId, StandardCharsets.UTF_8);
            return Optional.ofNullable(client.get().uri("/api/contents/" + encoded).retrieve().body(ContentMetadata.class));
        } catch (HttpClientErrorException.NotFound e) {
            return Optional.empty();
        } catch (RestClientException e) {
            throw new ContentUnavailableException("content-service lookup failed", e);
        }
    }
}
