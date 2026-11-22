package com.captionbridge.caption;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface CaptionRepository extends JpaRepository<CaptionLine, Long> {
    List<CaptionLine> findBySessionIdAndSeqGreaterThanOrderBySeqAsc(UUID sessionId, long after, Pageable page);
}
