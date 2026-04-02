package com.glmapper.coding.http.api.controller;

import com.glmapper.coding.core.catalog.SkillInfo;
import com.glmapper.coding.core.catalog.SkillsResolver;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/skills")
public class SkillsController {
    private final SkillsResolver skillsResolver;

    public SkillsController(SkillsResolver skillsResolver) {
        this.skillsResolver = skillsResolver;
    }

    @GetMapping
    public List<SkillInfo> list(@RequestParam String namespace) {
        return skillsResolver.resolveSkills(namespace);
    }

    @GetMapping("/{name}")
    public SkillInfo get(@RequestParam String namespace, @PathVariable String name) {
        return skillsResolver.resolveSkill(namespace, name)
                .orElseThrow(() -> new IllegalArgumentException("Skill not found: " + name));
    }

    @PostMapping("/reload")
    public void reload(@RequestParam(required = false) String namespace) {
        skillsResolver.invalidateCache(namespace);
    }
}
