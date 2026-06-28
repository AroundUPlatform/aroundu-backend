package com.beingadish.AroundU.user.entity;

import com.beingadish.AroundU.job.entity.Job;
import com.beingadish.AroundU.location.entity.Address;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonManagedReference;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Entity;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;

import java.time.LocalDateTime;
import java.util.List;

@Entity
@Table(name = "clients")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder
public class Client extends User {

    @jakarta.persistence.Column
    private LocalDateTime lastLoginAt;

    @jakarta.persistence.Column(nullable = false)
    @lombok.Builder.Default
    private Boolean deleted = false;

    // List of created jobs
    @OneToMany(mappedBy = "createdBy", cascade = CascadeType.ALL, orphanRemoval = true)
    @JsonIgnore
    private List<Job> postedJobs;

    // List of saved addresses
    @OneToMany(mappedBy = "client", cascade = CascadeType.ALL, orphanRemoval = true)
    @JsonManagedReference
    private List<Address> savedAddresses;
}
