package com.beingadish.AroundU.Service.impl;

import com.beingadish.AroundU.common.entity.Skill;
import com.beingadish.AroundU.common.mapper.SkillMapper;
import com.beingadish.AroundU.common.repository.SkillRepository;
import com.beingadish.AroundU.common.service.SkillService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SkillServiceTest {

    @Mock
    private SkillRepository skillRepository;

    @Mock
    private SkillMapper skillMapper;

    @InjectMocks
    private SkillService skillService;

    private Skill plumbingSkill;

    @BeforeEach
    void setUp() {
        plumbingSkill = new Skill();
        plumbingSkill.setId(1L);
        plumbingSkill.setName("plumbing");
    }

    @Test
    void findOrCreateSkills_returnsExistingSkillsWithoutCreating() {
        when(skillRepository.findAllByNameIgnoreCaseIn(List.of("plumbing")))
                .thenReturn(List.of(plumbingSkill));

        Set<Skill> result = skillService.findOrCreateSkills(List.of("  Plumbing  "));

        assertEquals(1, result.size());
        assertTrue(result.contains(plumbingSkill));
        verify(skillRepository, never()).save(any());
    }

    @Test
    void findOrCreateSkills_createsNewSkillWhenNotFound() {
        Skill newSkill = new Skill();
        newSkill.setId(2L);
        newSkill.setName("electrical");

        when(skillRepository.findAllByNameIgnoreCaseIn(List.of("electrical")))
                .thenReturn(List.of());
        when(skillRepository.save(any(Skill.class))).thenReturn(newSkill);

        Set<Skill> result = skillService.findOrCreateSkills(List.of("Electrical"));

        assertEquals(1, result.size());
        verify(skillRepository).save(any(Skill.class));
    }

    @Test
    void findOrCreateSkills_handlesOptimisticConcurrencyOnDuplicate() {
        when(skillRepository.findAllByNameIgnoreCaseIn(List.of("plumbing")))
                .thenReturn(List.of());
        when(skillRepository.save(any(Skill.class)))
                .thenThrow(new DataIntegrityViolationException("duplicate"));
        when(skillRepository.findByNameIgnoreCase("plumbing"))
                .thenReturn(Optional.of(plumbingSkill));

        Set<Skill> result = skillService.findOrCreateSkills(List.of("Plumbing"));

        assertEquals(1, result.size());
        assertTrue(result.contains(plumbingSkill));
    }

    @Test
    void findOrCreateSkills_deduplicatesInputNames() {
        when(skillRepository.findAllByNameIgnoreCaseIn(List.of("plumbing")))
                .thenReturn(List.of(plumbingSkill));

        Set<Skill> result = skillService.findOrCreateSkills(
                List.of("Plumbing", "  plumbing  ", "PLUMBING"));

        assertEquals(1, result.size());
    }

    @Test
    void findOrCreateSkills_filtersBlankNames() {
        when(skillRepository.findAllByNameIgnoreCaseIn(List.of("plumbing")))
                .thenReturn(List.of(plumbingSkill));

        Set<Skill> result = skillService.findOrCreateSkills(
                List.of("", "  ", "Plumbing"));

        assertEquals(1, result.size());
    }

    @Test
    void suggestSkills_returnsMatchingSkills() {
        when(skillRepository.suggestByName(eq("plu"), any(Pageable.class)))
                .thenReturn(List.of(plumbingSkill));

        var dtos = skillService.suggestSkills("plu", 10);

        assertNotNull(dtos);
        verify(skillRepository).suggestByName(eq("plu"), any(Pageable.class));
    }

    @Test
    void suggestSkills_capsLimitAt20() {
        when(skillRepository.suggestByName(eq("plu"), any(Pageable.class)))
                .thenReturn(List.of(plumbingSkill));

        // Request 50 but should be capped at 20
        skillService.suggestSkills("plu", 50);

        verify(skillRepository).suggestByName(eq("plu"), argThat(p
                -> p.getPageSize() == 20));
    }
}
