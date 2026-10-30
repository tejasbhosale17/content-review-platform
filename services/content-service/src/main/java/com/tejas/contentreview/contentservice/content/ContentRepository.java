package com.tejas.contentreview.contentservice.content;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ContentRepository extends JpaRepository<Content, String> {
}
