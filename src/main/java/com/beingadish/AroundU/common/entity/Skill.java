package com.beingadish.AroundU.common.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import com.beingadish.AroundU.job.entity.Job;

@Entity
@Table(name = "skills", uniqueConstraints = {
    @UniqueConstraint(name = "uk_skill_name_lower", columnNames = "name")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Skill {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(unique = true, nullable = false)
    @NotBlank
    private String name;

    /**
     * Normalises a raw skill name: trims, collapses whitespace, and lowercases.
     *
     * @param raw the raw input string
     * @return normalised skill name
     */
    public static String normalize(String raw) {
        if (raw == null) {
            return null;
        }
        return raw.strip().replaceAll("\\s+", " ").toLowerCase();
    }

    @ManyToMany(mappedBy = "skillSet")
    @Builder.Default
    @JsonIgnore
    private Set<Job> jobs = new HashSet<>();

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        Skill skill = (Skill) o;
        return id != null && Objects.equals(id, skill.id);
    }

    @Override
    public int hashCode() {
        return getClass().hashCode();
    }
}
