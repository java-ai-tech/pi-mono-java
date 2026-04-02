package com.glmapper.coding.core.catalog;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class ResourceCatalogServiceTest {
    @Test
    void shouldLoadSkillsPromptsAndResources(@TempDir Path tempDir) throws Exception {
        Path skillsDir = tempDir.resolve("skills");
        Path skill = skillsDir.resolve("my-skill").resolve("SKILL.md");
        Files.createDirectories(skill.getParent());
        Files.writeString(skill, "# My Skill\nSkill description", StandardCharsets.UTF_8);

        Path promptsDir = tempDir.resolve("prompts");
        Path prompt = promptsDir.resolve("refactor.prompt.md");
        Files.createDirectories(promptsDir);
        Files.writeString(prompt, "# Refactor Prompt\nPrompt body", StandardCharsets.UTF_8);

        Path resourcesDir = tempDir.resolve("resources");
        Path resource = resourcesDir.resolve("schema.json");
        Files.createDirectories(resourcesDir);
        Files.writeString(resource, "{\"name\":\"schema\"}", StandardCharsets.UTF_8);

        ResourceCatalogService service = new ResourceCatalogService(
                skillsDir.toString(),
                promptsDir.toString(),
                resourcesDir.toString()
        );

        assertEquals(1, service.skills().size());
        assertEquals("my-skill", service.skills().get(0).name());

        assertEquals(1, service.prompts().size());
        assertEquals("refactor.prompt", service.prompts().get(0).name());

        assertEquals(1, service.resources().size());
        assertEquals("schema", service.resources().get(0).name());
    }

    @Test
    void skillsByScopeShouldMatchOnlyExactScopeSegments(@TempDir Path tempDir) throws Exception {
        Path skillsDir = tempDir.resolve("skills");
        Path publicSkill = skillsDir.resolve("public").resolve("shared-skill").resolve("SKILL.md");
        Path namespaceSkill = skillsDir.resolve("namespaces").resolve("tenant-a").resolve("private-skill").resolve("SKILL.md");
        Path misleadingSkill = skillsDir.resolve("namespaces").resolve("public").resolve("wrong-skill").resolve("SKILL.md");
        Files.createDirectories(publicSkill.getParent());
        Files.createDirectories(namespaceSkill.getParent());
        Files.createDirectories(misleadingSkill.getParent());
        Files.writeString(publicSkill, "# Shared\nPublic", StandardCharsets.UTF_8);
        Files.writeString(namespaceSkill, "# Private\nTenant", StandardCharsets.UTF_8);
        Files.writeString(misleadingSkill, "# Wrong\nShould not match public", StandardCharsets.UTF_8);

        ResourceCatalogService service = new ResourceCatalogService(skillsDir.toString(), "", "");

        assertEquals(1, service.skillsByScope("public").size());
        assertEquals("shared-skill", service.skillsByScope("public").get(0).name());
        assertEquals(1, service.skillsByScope("namespaces/tenant-a").size());
        assertEquals("private-skill", service.skillsByScope("namespaces/tenant-a").get(0).name());
    }
}
