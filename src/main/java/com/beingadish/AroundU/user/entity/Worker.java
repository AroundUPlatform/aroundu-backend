package com.beingadish.AroundU.user.entity;

import com.beingadish.AroundU.job.entity.Job;
import com.beingadish.AroundU.review.entity.Review;
import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import lombok.*;
import lombok.experimental.SuperBuilder;

import java.time.LocalDateTime;
import java.util.List;

@Entity
@Table(name = "workers")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder
public class Worker extends User {

    @Column
    private LocalDateTime lastLoginAt;

    @Column(nullable = false)
    @Builder.Default
    private Boolean deleted = false;

    @OneToMany(mappedBy = "assignedTo")
    @JsonIgnore
    private List<Job> engagedJobList;

    private Double overallRating;

    private Integer experienceYears;

    @Column(length = 1000)
    private String certifications;

    @Builder.Default
    private Boolean isOnDuty = false;

    @Column(length = 255)
    private String payoutAccount;

    @OneToMany(mappedBy = "worker")
    @JsonIgnore
    private List<Review> reviews;

    // ── Cancellation penalty fields ───────────────────────────
    /**
     * Number of times this worker has cancelled an accepted/in-progress job.
     */
    @Builder.Default
    @Column(nullable = false)
    private Integer cancellationCount = 0;

    /**
     * If non-null, the worker is blocked from accepting new jobs until this
     * timestamp.
     */
    @Column
    private LocalDateTime blockedUntil;

    /**
     * Returns true if this worker is currently serving a cancellation block.
     */
    public boolean isBlocked() {
        return blockedUntil != null && LocalDateTime.now().isBefore(blockedUntil);
    }

    /**
     * Increment cancellation count and return the new value.
     */
    public int incrementCancellationCount() {
        if (cancellationCount == null) {
            cancellationCount = 0;
        }
        return ++cancellationCount;
    }
}
