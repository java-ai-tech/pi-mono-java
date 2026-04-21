package com.glmapper.coding.core.catalog;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.yaml.snakeyaml.Yaml;

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
    private static final Logger log = LoggerFactory.getLogger(ResourceCatalogService.class);
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
        } catch (IOException e) {
            log.warn("Failed to walk skills directory: {}", root, e);
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
        } catch (IOException e) {
            log.warn("Failed to walk prompts directory: {}", root, e);
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
        } catch (IOException e) {
            log.warn("Failed to walk resources directory: {}", root, e);
        }
    }

    private void readSkill(Path path) {
        try {
            String content = Files.readString(path, StandardCharsets.UTF_8);
            String name = path.getParent() != null ? path.getParent().getFileName().toString() : baseName(path);

            Map<String, Object> frontmatter = parseFrontmatter(content);
            String entrypoint = stringFromFrontmatter(frontmatter, "entrypoint");
            String argsSchema = stringFromFrontmatter(frontmatter, "args_schema");
            long timeoutMs = longFromFrontmatter(frontmatter, "timeout_ms", 0);

            // Prefer explicit description from frontmatter, fall back to first non-empty line
            String description = stringFromFrontmatter(frontmatter, "description");
            if (description == null || description.isBlank()) {
                description = firstNonEmptyLine(content).orElse("Skill");
            }

            SkillInfo skill = new SkillInfo(normalizeName(name), description, path.toAbsolutePath().toString(),
                    content, entrypoint, argsSchema, timeoutMs);
            skills.put(path.toAbsolutePath().toString(), skill);
        } catch (IOException e) {
            log.warn("Failed to read skill file: {}", path, e);
        }
    }

    private void readPrompt(Path path) {
        try {
            String content = Files.readString(path, StandardCharsets.UTF_8);
            String description = firstNonEmptyLine(content).orElse("Prompt template");
            String name = normalizeName(baseName(path));
            prompts.put(name, new PromptTemplateInfo(name, description, path.toAbsolutePath().toString(), content));
        } catch (IOException e) {
            log.warn("Failed to read prompt file: {}", path, e);
        }
    }

    private void readResource(Path path) {
        try {
            String content = Files.readString(path, StandardCharsets.UTF_8);
            String name = normalizeName(baseName(path));
            String type = extension(path.getFileName().toString());
            resources.put(name, new ResourceInfo(name, type, path.toAbsolutePath().toString(), content));
        } catch (IOException e) {
            log.warn("Failed to read resource file: {}", path, e);
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

    /**
     * Parse YAML frontmatter using SnakeYAML for proper multi-line and nested value support.
     */
    private Map<String, Object> parseFrontmatter(String content) {
        if (content == null || !content.startsWith("---")) {
            return Map.of();
        }
        int endIdx = content.indexOf("\n---", 3);
        if (endIdx < 0) {
            return Map.of();
        }
        String frontmatterYaml = content.substring(3, endIdx).trim();
        if (frontmatterYaml.isBlank()) {
            return Map.of();
        }

        try {
            Yaml yaml = new Yaml();
            Object parsed = yaml.load(frontmatterYaml);
            if (parsed instanceof Map<?, ?> map) {
                Map<String, Object> result = new LinkedHashMap<>();
                for (Map.Entry<?, ?> entry : map.entrySet()) {
                    if (entry.getKey() != null) {
                        result.put(String.valueOf(entry.getKey()), entry.getValue());
                    }
                }
                return result;
            }
        } catch (Exception e) {
            log.warn("Failed to parse YAML frontmatter, falling back to empty map: {}", e.getMessage());
        }
        return Map.of();
    }

    private String stringFromFrontmatter(Map<String, Object> frontmatter, String key) {
        Object value = frontmatter.get(key);
        if (value == null) {
            return null;
        }
        String str = String.valueOf(value).trim();
        return str.isEmpty() ? null : str;
    }

    private long longFromFrontmatter(Map<String, Object> frontmatter, String key, long defaultValue) {
        Object value = frontmatter.get(key);
        if (value instanceof Number num) {
            return num.longValue();
        }
        if (value instanceof String str) {
            try {
                return Long.parseLong(str.trim());
            } catch (NumberFormatException ignored) {
            }
        }
        return defaultValue;
    }
}
