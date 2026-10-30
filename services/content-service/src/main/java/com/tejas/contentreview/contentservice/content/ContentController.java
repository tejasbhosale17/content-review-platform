package com.tejas.contentreview.contentservice.content;

import com.tejas.contentreview.contentservice.content.ContentDtos.ContentResponse;
import com.tejas.contentreview.contentservice.content.ContentDtos.CreateContentRequest;
import com.tejas.contentreview.contentservice.web.NotFoundException;
import jakarta.validation.Valid;
import java.time.Instant;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/contents")
public class ContentController {

    private final ContentRepository repository;

    public ContentController(ContentRepository repository) {
        this.repository = repository;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ContentResponse create(@Valid @RequestBody CreateContentRequest request) {
        Content content = new Content(UUID.randomUUID().toString(), request.creatorId().trim(), request.title().trim(),
                request.licenseValid(), request.licenseNote(), Instant.now());
        return ContentResponse.from(repository.save(content));
    }

    @GetMapping("/{contentId}")
    public ContentResponse get(@PathVariable String contentId) {
        return repository.findById(contentId)
                .map(ContentResponse::from)
                .orElseThrow(() -> new NotFoundException("Content " + contentId + " not found"));
    }
}
