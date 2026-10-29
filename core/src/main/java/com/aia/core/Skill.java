package com.aia.core;

import java.util.List;
import java.util.Map;

public interface Skill {
    String name();

    String description();

    String instructions();

    List<Tool> tools();

    Map<String, Object> resources();
}
