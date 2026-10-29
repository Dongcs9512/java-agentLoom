package com.aia.server;

import com.aia.core.AgentConfig;
import com.aia.core.SkillConfig;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class AgentController {

    private final AgentRuntime runtime;

    public AgentController(AgentRuntime runtime) {
        this.runtime = runtime;
    }

    @GetMapping("/agents")
    public List<AgentConfig> agents() {
        return runtime.listAgents();
    }

    @PostMapping("/agents")
    public void saveAgent(@RequestBody AgentConfig config) {
        runtime.saveAgent(config);
    }

    @DeleteMapping("/agents/{id}")
    public void deleteAgent(@PathVariable String id) {
        runtime.deleteAgent(id);
    }

    @GetMapping("/skills")
    public List<SkillConfig> skills() {
        return runtime.listSkills();
    }

    @PostMapping("/skills")
    public void saveSkill(@RequestBody SkillConfig config) {
        runtime.saveSkill(config);
    }

    @DeleteMapping("/skills/{name}")
    public void deleteSkill(@PathVariable String name) {
        runtime.deleteSkill(name);
    }

    @PostMapping("/chat/{agentId}")
    public Map<String, String> chat(@PathVariable String agentId, @RequestBody Map<String, String> body) {
        String answer = runtime.chat(agentId, body.getOrDefault("message", ""));
        return Map.of("answer", answer);
    }
}
