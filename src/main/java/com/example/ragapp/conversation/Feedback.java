package com.example.ragapp.conversation;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

/** A user's rating of one answer. Used to find answers that need improving. */
@Entity
@Table(name = "feedback")
public class Feedback {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "message_id", nullable = false, unique = true)
    private Long messageId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private FeedbackRating rating;

    private String comment;

    @Column(name = "reported_incorrect", nullable = false)
    private boolean reportedIncorrect;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    protected Feedback() {
        // required by JPA
    }

    public Feedback(Long messageId) {
        this.messageId = messageId;
    }

    @PreUpdate
    void onUpdate() {
        this.updatedAt = Instant.now();
    }

    public void update(FeedbackRating rating, String comment, boolean reportedIncorrect) {
        this.rating = rating;
        this.comment = comment;
        this.reportedIncorrect = reportedIncorrect;
    }

    public Long getMessageId() { return messageId; }
    public FeedbackRating getRating() { return rating; }
    public String getComment() { return comment; }
    public boolean isReportedIncorrect() { return reportedIncorrect; }
}
