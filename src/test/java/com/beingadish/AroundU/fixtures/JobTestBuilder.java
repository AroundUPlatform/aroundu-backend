package com.beingadish.AroundU.fixtures;

import com.beingadish.AroundU.common.constants.enums.Currency;
import com.beingadish.AroundU.common.constants.enums.JobStatus;
import com.beingadish.AroundU.common.constants.enums.JobUrgency;
import com.beingadish.AroundU.common.constants.enums.PaymentMode;
import com.beingadish.AroundU.common.entity.Price;
import com.beingadish.AroundU.common.entity.Skill;
import com.beingadish.AroundU.job.entity.Job;
import com.beingadish.AroundU.location.entity.Address;
import com.beingadish.AroundU.user.entity.Client;
import com.beingadish.AroundU.user.entity.Worker;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.Set;

/**
 * Fluent builder for {@link Job} entities in tests.
 */
public final class JobTestBuilder {

    private Long id = 100L;
    private String title = "Fix plumbing";
    private String shortDescription = "Kitchen sink leaking";
    private String longDescription = "The kitchen sink has been leaking for 2 days";
    private Price price = new Price(Currency.USD, 500.0);
    private Address jobLocation = TestFixtures.address();
    private JobStatus jobStatus = JobStatus.OPEN_FOR_BIDS;
    private JobUrgency jobUrgency = JobUrgency.NORMAL;
    private PaymentMode paymentMode = PaymentMode.ESCROW;
    private Set<Skill> skillSet = TestFixtures.plumbingSkills();
    private Client createdBy = TestFixtures.client();
    private Worker assignedTo = null;
    private LocalDateTime createdAt = LocalDateTime.now();
    private final LocalDateTime updatedAt = LocalDateTime.now();

    private JobTestBuilder() {
    }

    public static JobTestBuilder aJob() {
        return new JobTestBuilder();
    }

    public JobTestBuilder withId(Long id) {
        this.id = id;
        return this;
    }

    public JobTestBuilder withTitle(String title) {
        this.title = title;
        return this;
    }

    public JobTestBuilder withShortDescription(String desc) {
        this.shortDescription = desc;
        return this;
    }

    public JobTestBuilder withLongDescription(String desc) {
        this.longDescription = desc;
        return this;
    }

    public JobTestBuilder withPrice(double amount) {
        this.price = new Price(Currency.USD, amount);
        return this;
    }

    public JobTestBuilder withLocation(Address location) {
        this.jobLocation = location;
        return this;
    }

    public JobTestBuilder withStatus(JobStatus status) {
        this.jobStatus = status;
        return this;
    }

    public JobTestBuilder withUrgency(JobUrgency urgency) {
        this.jobUrgency = urgency;
        return this;
    }

    public JobTestBuilder withPaymentMode(PaymentMode mode) {
        this.paymentMode = mode;
        return this;
    }

    public JobTestBuilder withSkills(Set<Skill> skills) {
        this.skillSet = skills;
        return this;
    }

    public JobTestBuilder withClientId(Long clientId) {
        this.createdBy = TestFixtures.client(clientId);
        return this;
    }

    public JobTestBuilder withClient(Client client) {
        this.createdBy = client;
        return this;
    }

    public JobTestBuilder withWorker(Worker worker) {
        this.assignedTo = worker;
        return this;
    }

    public JobTestBuilder withCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
        return this;
    }

    public Job build() {
        return Job.builder()
                .id(id)
                .title(title)
                .shortDescription(shortDescription)
                .longDescription(longDescription)
                .price(price)
                .jobLocation(jobLocation)
                .jobStatus(jobStatus)
                .jobUrgency(jobUrgency)
                .paymentMode(paymentMode)
                .skillSet(new HashSet<>(skillSet))
                .createdBy(createdBy)
                .assignedTo(assignedTo)
                .createdAt(createdAt)
                .updatedAt(updatedAt)
                .build();
    }
}
