package com.glmapper.coding.core.service;

import com.glmapper.agent.core.AgentTool;
import com.glmapper.coding.core.catalog.SkillInfo;
import com.glmapper.coding.core.catalog.SkillsResolver;
import com.glmapper.coding.core.config.BuiltinToolsProperties;
import com.glmapper.coding.core.execution.ExecutionBackend;
import com.glmapper.coding.core.tools.builtin.BuiltinToolFactory;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentToolFactoryBuiltinToolsTest {

    @Test
    void shouldComposeBuiltinAndSkillTools() {
        SkillsResolver skillsResolver = Mockito.mock(SkillsResolver.class);
        ExecutionBackend backend = Mockito.mock(ExecutionBackend.class);

        Mockito.when(skillsResolver.resolveSkills("tenant-a")).thenReturn(List.of(
                new SkillInfo("deploy", "Deploy service", "/skills/deploy/SKILL.md", "# deploy")
        ));

        BuiltinToolsProperties properties = new BuiltinToolsProperties();
        BuiltinToolFactory builtinToolFactory = new BuiltinToolFactory(backend, properties);
        AgentToolFactory factory = new AgentToolFactory(skillsResolver, backend, builtinToolFactory);

        List<AgentTool> tools = factory.createTools("tenant-a", "s-1");
        List<String> names = tools.stream().map(AgentTool::name).toList();

        assertTrue(names.contains("read"));
        assertTrue(names.contains("bash"));
        assertTrue(names.contains("edit"));
        assertTrue(names.contains("write"));
        assertTrue(names.contains("skill_deploy"));
    }
}
