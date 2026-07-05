---
name: make-new-skill
description: "Guides the user through creating a new custom skill for the LLM Capability Runner. It provides a standard markdown template and explains the best practices for structuring skill prompts."
---

# Make New Skill

When the user asks to create a new skill, follow these steps to guide them:

1. **Understand the Goal**: Ask the user what specific task or capability the new skill should focus on.
2. **Draft the Frontmatter**: Explain that all skills need a YAML frontmatter block with a `name` and `description`.
3. **Structure the Body**: Help the user write the body of the skill. A good skill should include:
   - A clear objective.
   - Step-by-step instructions for the model.
   - Any specific constraints or formatting requirements (e.g., "always output JSON").
4. **Provide the Template**: Give the user the following markdown template to fill out:

```markdown
---
name: your-skill-name
description: A short 1-2 sentence description of when the model should use this skill.
---

# [Skill Title]

## Objective
[State the primary goal of this skill]

## Instructions
1. [First step the model should take]
2. [Second step]

## Formatting Requirements
[Any specific rules for how the output should look]
```

5. **Finalize**: Instruct the user to save this as a `.md` file and use the "Import Skill" button in the Runner's Settings to load it.
