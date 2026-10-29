package com.aia.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class SkillRegistry {

    private final Map<String, Skill> skills = new LinkedHashMap<>();

    public void register(Skill skill) {
        if (skills.containsKey(skill.name())) {
            throw new IllegalArgumentException("技能已存在: " + skill.name());
        }
        skills.put(skill.name(), skill);
    }

    public Optional<Skill> get(String name) {
        return Optional.ofNullable(skills.get(name));
    }

    public List<Skill> all() {
        return new ArrayList<>(skills.values());
    }
}
