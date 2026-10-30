package io.github.dongcs9512.agentloom.core;

import java.util.List;

public record SkillConfig(String name, String description, String instructions, List<String> toolNames) {
}
