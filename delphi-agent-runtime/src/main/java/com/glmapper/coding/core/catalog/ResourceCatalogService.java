package com.glmapper.coding.core.catalog;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

@Service
public class ResourceCatalogService {
    private final String skillsDirsConfig;
    private final String promptsDirsConfig;
    private final String resourcesDirsConfig;

    private final Map<String, SkillInfo> skills = new ConcurrentHashMap<>();
    private final Map<String, PromptTemplateInfo> prompts = new ConcurrentHashMap<>();
    private final Map<String, ResourceInfo> resources = new ConcurrentHashMap<>();

    public ResourceCatalogService(
            @Value("${pi.resources.skills-dirs:${PI_AGENT_SKILLS_DIRS:./skills,${user.home}/.codex/skills}}") String skillsDirsConfig,
            @Value("${pi.resources.prompts-dirs:${PI_AGENT_PROMPTS_DIRS:./prompts}}") String promptsDirsConfig,
            @Value("${pi.resources.resources-dirs:${PI_AGENT_RESOURCES_DIRS:./resources}}") String resourcesDirsConfig
    ) {
        this.skillsDirsConfig = skillsDirsConfig;
        this.promptsDirsConfig = promptsDirsConfig;
        this.resourcesDirsConfig = resourcesDirsConfig;
        reload();
    }


    public synchronized void reload() {
        skills.clear();
        prompts.clear();
        resources.clear();

        for (Path dir : parseDirs(skillsDirsConfig)) {
            loadSkills(dir);
        }
        for (Path dir : parseDirs(promptsDirsConfig)) {
            loadPrompts(dir);
        }
        for (Path dir : parseDirs(resourcesDirsConfig)) {
            loadResources(dir);
        }
    }

    public List<SkillInfo> skills() {
        return skills.values().stream()
                .sorted(Comparator.comparing(SkillInfo::name))
                .toList();
    }

    public List<SkillInfo> skillsByScope(String scope) {
        return skills.values().stream()
                .filter(skill -> matchesSkillScope(skill.path(), scope))
                .sorted(Comparator.comparing(SkillInfo::name))
                .toList();
    }

    public Optional<SkillInfo> skill(String name) {
        String normalized = normalizeName(name);
        return skills.values().stream()
                .filter(s -> s.name().equals(normalized))
                .findFirst();
    }

    public List<PromptTemplateInfo> prompts() {
        return prompts.values().stream()
                .sorted(Comparator.comparing(PromptTemplateInfo::name))
                .toList();
    }

    public Optional<PromptTemplateInfo> prompt(String name) {
        return Optional.ofNullable(prompts.get(normalizeName(name)));
    }

    public List<ResourceInfo> resources() {
        return resources.values().stream()
                .sorted(Comparator.comparing(ResourceInfo::name))
                .toList();
    }

    public Optional<ResourceInfo> resource(String name) {
        return Optional.ofNullable(resources.get(normalizeName(name)));
    }

    public List<SlashCommandInfo> slashCommands(List<SlashCommandInfo> extensionCommands) {
        Map<String, SlashCommandInfo> merged = new LinkedHashMap<>();

        for (PromptTemplateInfo prompt : prompts()) {
            merged.put(prompt.name(), new SlashCommandInfo(
                    prompt.name(),
                    prompt.description(),
                    "prompt",
                    "project",
                    prompt.path()
            ));
        }

        for (SkillInfo skill : skills()) {
            merged.put(skill.name(), new SlashCommandInfo(
                    skill.name(),
                    skill.description(),
                    "skill",
                    "project",
                    skill.path()
            ));
        }

        if (extensionCommands != null) {
            for (SlashCommandInfo extensionCommand : extensionCommands) {
                merged.put(extensionCommand.name(), extensionCommand);
            }
        }

        return new ArrayList<>(merged.values());
    }

    private void loadSkills(Path root) {
        if (!Files.exists(root)) {
            return;
        }
        try (Stream<Path> stream = Files.walk(root)) {
            stream.filter(path -> Files.isRegularFile(path) && path.getFileName().toString().equals("SKILL.md"))
                    .forEach(this::readSkill);
        } catch (IOException ignored) {
        }
    }

    private boolean matchesSkillScope(String skillPath, String scope) {
        Path normalizedSkillPath = Paths.get(skillPath).toAbsolutePath().normalize();
        // Check each configured skills root to find the relative path
        for (Path root : parseDirs(skillsDirsConfig)) {
            Path absRoot = root.toAbsolutePath().normalize();
            if (normalizedSkillPath.startsWith(absRoot)) {
                Path relative = absRoot.relativize(normalizedSkillPath);
                // The relative path should start with the scope segments
                String[] scopeSegments = scope.split("/");
                if (relative.getNameCount() < scopeSegments.length) {
                    continue;
                }
                boolean matched = true;
                for (int j = 0; j < scopeSegments.length; j++) {
                    if (!relative.getName(j).toString().equals(scopeSegments[j])) {
                        matched = false;
                        break;
                    }
                }
                if (matched) {
                    return true;
                }
            }
        }
        return false;
    }

    private void loadPrompts(Path root) {
        if (!Files.exists(root)) {
            return;
        }
        try (Stream<Path> stream = Files.walk(root)) {
            stream.filter(path -> Files.isRegularFile(path) && isPromptFile(path.getFileName().toString()))
                    .forEach(this::readPrompt);
        } catch (IOException ignored) {
        }
    }

    private void loadResources(Path root) {
        if (!Files.exists(root)) {
            return;
        }
        try (Stream<Path> stream = Files.walk(root)) {
            stream.filter(Files::isRegularFile)
                    .filter(path -> !path.getFileName().toString().equals("SKILL.md"))
                    .filter(path -> !isPromptFile(path.getFileName().toString()))
                    .forEach(this::readResource);
        } catch (IOException ignored) {
        }
    }

    private void readSkill(Path path) {
        try {
            String content = Files.readString(path, StandardCharsets.UTF_8);
            String name = path.getParent() != null ? path.getParent().getFileName().toString() : baseName(path);
            String description = firstNonEmptyLine(content).orElse("Skill");
            String entrypoint = extractFrontmatterField(content, "entrypoint");
            String argsSchema = extractFrontmatterField(content, "args_schema");
            SkillInfo skill = new SkillInfo(normalizeName(name), description, path.toAbsolutePath().toString(),
                    content, entrypoint, argsSchema);
            // Use absolute path as key to allow same-name skills in different scopes
            skills.put(path.toAbsolutePath().toString(), skill);
        } catch (IOException ignored) {
        }
    }

    private void readPrompt(Path path) {
        try {
            String content = Files.readString(path, StandardCharsets.UTF_8);
            String description = firstNonEmptyLine(content).orElse("Prompt template");
            String name = normalizeName(baseName(path));
            prompts.put(name, new PromptTemplateInfo(name, description, path.toAbsolutePath().toString(), content));
        } catch (IOException ignored) {
        }
    }

    private void readResource(Path path) {
        try {
            String content = Files.readString(path, StandardCharsets.UTF_8);
            String name = normalizeName(baseName(path));
            String type = extension(path.getFileName().toString());
            resources.put(name, new ResourceInfo(name, type, path.toAbsolutePath().toString(), content));
        } catch (IOException ignored) {
        }
    }

    private List<Path> parseDirs(String config) {
        if (config == null || config.isBlank()) {
            return List.of();
        }
        String[] parts = config.split(",");
        List<Path> paths = new ArrayList<>();
        for (String part : parts) {
            String p = part.trim();
            if (p.isEmpty()) {
                continue;
            }
            if (p.startsWith("~")) {
                p = System.getProperty("user.home") + p.substring(1);
            }
            paths.add(Paths.get(p));
        }
        return paths;
    }

    private boolean isPromptFile(String filename) {
        String lower = filename.toLowerCase(Locale.ROOT);
        return lower.endsWith(".prompt.md") || lower.endsWith(".prompt.txt") || lower.endsWith(".prompt");
    }

    private String normalizeName(String value) {
        if (value == null) {
            return "";
        }
        return value.trim().toLowerCase(Locale.ROOT).replace(' ', '-');
    }

    private Optional<String> firstNonEmptyLine(String content) {
        if (content == null || content.isBlank()) {
            return Optional.empty();
        }

        String text = content;
        if (text.startsWith("---")) {
            int endIdx = text.indexOf("\n---", 3);
            if (endIdx >= 0) {
                text = text.substring(endIdx + 4);
            }
        }

        String[] lines = text.split("\n");
        for (String line : lines) {
            String trimmed = line.trim();
            if (!trimmed.isBlank()) {
                return Optional.of(trimmed.replaceFirst("^#+\\s*", ""));
            }
        }
        return Optional.empty();
    }

    private String baseName(Path path) {
        String name = path.getFileName().toString();
        int idx = name.lastIndexOf('.');
        return idx > 0 ? name.substring(0, idx) : name;
    }

    private String extension(String filename) {
        int idx = filename.lastIndexOf('.');
        return idx >= 0 ? filename.substring(idx + 1).toLowerCase(Locale.ROOT) : "txt";
    }

    private String extractFrontmatterField(String content, String field) {
        if (content == null || !content.startsWith("---")) {
            return null;
        }
        int endIdx = content.indexOf("---", 3);
        if (endIdx < 0) {
            return null;
        }
        String frontmatter = content.substring(3, endIdx);
        for (String line : frontmatter.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.startsWith(field + ":")) {
                String value = trimmed.substring(field.length() + 1).trim();
                if ((value.startsWith("\"") && value.endsWith("\""))
                        || (value.startsWith("'") && value.endsWith("'"))) {
                    value = value.substring(1, value.length() - 1);
                }
                return value.isEmpty() ? null : value;
            }
        }
        return null;
    }
}
