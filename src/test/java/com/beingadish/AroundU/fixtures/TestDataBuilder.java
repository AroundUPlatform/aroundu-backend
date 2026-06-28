package com.beingadish.AroundU.fixtures;

import com.beingadish.AroundU.bid.entity.Bid;
import com.beingadish.AroundU.common.constants.enums.BidStatus;
import com.beingadish.AroundU.common.constants.enums.PaymentMode;
import com.beingadish.AroundU.common.constants.enums.PaymentStatus;
import com.beingadish.AroundU.job.entity.Job;
import com.beingadish.AroundU.payment.entity.PaymentTransaction;
import com.beingadish.AroundU.user.entity.Client;
import com.beingadish.AroundU.user.entity.Worker;

import java.time.LocalDateTime;

/**
 * Convenience builder helpers for test data that don't fit in
 * {@link JobTestBuilder}.
 */
public final class TestDataBuilder {

    private TestDataBuilder() {
    }

    // ── Bid builder ──────────────────────────────────────────────
    public static BidBuilder aBid() {
        return new BidBuilder();
    }

    // ── PaymentTransaction builder ───────────────────────────────
    public static PaymentBuilder aPayment() {
        return new PaymentBuilder();
    }

    public static class BidBuilder {

        private Long id = 200L;
        private Job job = TestFixtures.job();
        private Worker worker = TestFixtures.worker();
        private Double bidAmount = 450.0;
        private String notes = "I can fix this";
        private BidStatus status = BidStatus.PENDING;

        public BidBuilder withId(Long id) {
            this.id = id;
            return this;
        }

        public BidBuilder withJob(Job job) {
            this.job = job;
            return this;
        }

        public BidBuilder withWorker(Worker worker) {
            this.worker = worker;
            return this;
        }

        public BidBuilder withAmount(Double amount) {
            this.bidAmount = amount;
            return this;
        }

        public BidBuilder withNotes(String notes) {
            this.notes = notes;
            return this;
        }

        public BidBuilder withStatus(BidStatus status) {
            this.status = status;
            return this;
        }

        public Bid build() {
            return Bid.builder()
                    .id(id)
                    .job(job)
                    .worker(worker)
                    .bidAmount(bidAmount)
                    .notes(notes)
                    .status(status)
                    .createdAt(LocalDateTime.now())
                    .updatedAt(LocalDateTime.now())
                    .build();
        }
    }

    public static class PaymentBuilder {

        private Long id = 300L;
        private Job job = TestFixtures.job();
        private Client client = TestFixtures.client();
        private Worker worker = TestFixtures.worker();
        private Double amount = 500.0;
        private final PaymentMode paymentMode = PaymentMode.ESCROW;
        private PaymentStatus status = PaymentStatus.ESCROW_LOCKED;

        public PaymentBuilder withId(Long id) {
            this.id = id;
            return this;
        }

        public PaymentBuilder withJob(Job job) {
            this.job = job;
            return this;
        }

        public PaymentBuilder withClient(Client client) {
            this.client = client;
            return this;
        }

        public PaymentBuilder withWorker(Worker worker) {
            this.worker = worker;
            return this;
        }

        public PaymentBuilder withAmount(Double amount) {
            this.amount = amount;
            return this;
        }

        public PaymentBuilder withStatus(PaymentStatus status) {
            this.status = status;
            return this;
        }

        public PaymentTransaction build() {
            return PaymentTransaction.builder()
                    .id(id)
                    .job(job)
                    .client(client)
                    .worker(worker)
                    .amount(amount)
                    .paymentMode(paymentMode)
                    .status(status)
                    .createdAt(LocalDateTime.now())
                    .updatedAt(LocalDateTime.now())
                    .build();
        }
    }
}
