package com.tejas.contentreview.caseservice.cases;

import org.springframework.data.jpa.repository.JpaRepository;

public interface CaseRepository extends JpaRepository<CaseEntity, String> {
}
