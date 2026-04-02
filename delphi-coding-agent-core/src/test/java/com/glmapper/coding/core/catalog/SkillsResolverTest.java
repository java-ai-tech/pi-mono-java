package com.glmapper.coding.core.catalog;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class SkillsResolverTest {

    @Test
    void shouldMergePublicAndNamespaceSkills(@TempDir Path skillsDir) throws Exception {
        // Create public skill
        Path publicSkill = skillsDir.resolve("public").resolve("shared-tool").resolve("SKILL.md");
        Files.createDirectories(publicSkill.getParent());
        Files.writeString(publicSkill, "# Shared Tool\nPublic skill content", StandardCharsets.UTF_8);

        // Create namespace-specific skill
        Path nsSkill = skillsDir.resolve("namespaces").resolve("tenant-a").resolve("private-tool").resolve("SKILL.md");
        Files.createDirectories(nsSkill.getParent());
        Files.writeString(nsSkill, "# Private Tool\nTenant-a only", StandardCharsets.UTF_8);

        ResourceCatalogService catalog = new ResourceCatalogService(skillsDir.toString(), "", "");
        SkillsResolver resolver = new SkillsResolver(catalog);

        List<SkillInfo> skills = resolver.resolveSkills("tenant-a");
        assertEquals(2, skills.size());
        assertTrue(skills.stream().anyMatch(s -> s.name().equals("shared-tool")));
        assertTrue(skills.stream().anyMatch(s -> s.name().equals("private-tool")));
    }

    @Test
    void tenantBShouldNotSeeTenantASkills(@TempDir Path skillsDir) throws Exception {
        Path nsSkillA = skillsDir.resolve("namespaces").resolve("tenant-a").resolve("secret-tool").resolve("SKILL.md");
        Files.createDirectories(nsSkillA.getParent());
        Files.writeString(nsSkillA, "# Secret\nTenant-a only", StandardCharsets.UTF_8);

        ResourceCatalogService catalog = new ResourceCatalogService(skillsDir.toString(), "", "");
        SkillsResolver resolver = new SkillsResolver(catalog);

        List<SkillInfo> skillsB = resolver.resolveSkills("tenant-b");
        assertTrue(skillsB.isEmpty(), "tenant-b should not see tenant-a skills");

        List<SkillInfo> skillsA = resolver.resolveSkills("tenant-a");
        assertEquals(1, skillsA.size());
        assertEquals("secret-tool", skillsA.get(0).name());
    }

    @Test
    void namespaceSkillShouldCoexistWithPublicSkill(@TempDir Path skillsDir) throws Exception {
        Path publicSkill = skillsDir.resolve("public").resolve("deploy-generic").resolve("SKILL.md");
        Files.createDirectories(publicSkill.getParent());
        Files.writeString(publicSkill, "# Deploy Generic\nGeneric deploy", StandardCharsets.UTF_8);

        Path nsSkill = skillsDir.resolve("namespaces").resolve("tenant-a").resolve("deploy-custom").resolve("SKILL.md");
        Files.createDirectories(nsSkill.getParent());
        Files.writeString(nsSkill, "# Deploy Custom\nCustom tenant-a deploy", StandardCharsets.UTF_8);

        ResourceCatalogService catalog = new ResourceCatalogService(skillsDir.toString(), "", "");
        SkillsResolver resolver = new SkillsResolver(catalog);

        List<SkillInfo> skills = resolver.resolveSkills("tenant-a");
        assertEquals(2, skills.size());
        assertTrue(skills.stream().anyMatch(s -> s.name().equals("deploy-generic")));
        assertTrue(skills.stream().anyMatch(s -> s.name().equals("deploy-custom")));

        // tenant-b should only see public
        List<SkillInfo> skillsB = resolver.resolveSkills("tenant-b");
        assertEquals(1, skillsB.size());
        assertEquals("deploy-generic", skillsB.get(0).name());
    }

    @Test
    void resolveSkillByNameShouldWork(@TempDir Path skillsDir) throws Exception {
        Path publicSkill = skillsDir.resolve("public").resolve("lint").resolve("SKILL.md");
        Files.createDirectories(publicSkill.getParent());
        Files.writeString(publicSkill, "# Lint\nRun linter", StandardCharsets.UTF_8);

        ResourceCatalogService catalog = new ResourceCatalogService(skillsDir.toString(), "", "");
        SkillsResolver resolver = new SkillsResolver(catalog);

        Optional<SkillInfo> found = resolver.resolveSkill("any-tenant", "lint");
        assertTrue(found.isPresent());
        assertEquals("lint", found.get().name());

        Optional<SkillInfo> notFound = resolver.resolveSkill("any-tenant", "nonexistent");
        assertTrue(notFound.isEmpty());
    }

    @Test
    void cacheInvalidationShouldWork(@TempDir Path skillsDir) throws Exception {
        Path publicSkill = skillsDir.resolve("public").resolve("tool").resolve("SKILL.md");
        Files.createDirectories(publicSkill.getParent());
        Files.writeString(publicSkill, "# Tool\nV1", StandardCharsets.UTF_8);

        ResourceCatalogService catalog = new ResourceCatalogService(skillsDir.toString(), "", "");
        SkillsResolver resolver = new SkillsResolver(catalog);

        List<SkillInfo> v1 = resolver.resolveSkills("tenant-a");
        assertEquals(1, v1.size());

        // Invalidate and re-resolve (cache cleared, but catalog is static)
        resolver.invalidateCache("tenant-a");
        List<SkillInfo> v2 = resolver.resolveSkills("tenant-a");
        assertEquals(1, v2.size());
    }
}