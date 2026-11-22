package com.captionbridge.session;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface SessionRepository extends JpaRepository<Session, UUID> {
    boolean existsByStatus(SessionStatus status);

    @Query(value = "SELECT id FROM session_creation_guard WHERE id = 1 FOR UPDATE", nativeQuery = true)
    int lockCreationGuard();

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from Session s where s.id = :id")
    Optional<Session> findByIdForUpdate(@Param("id") UUID id);
}
