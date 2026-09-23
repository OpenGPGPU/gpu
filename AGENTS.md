# Agent contribution rules

When creating a Git commit, include a `Co-authored-by` trailer for the agent. The trailer must identify the agent and include the model name, for example:

```text
Co-authored-by: Codex (GPT-6 sol) <codex@openai.com>
```

# Language & Translation Rules

- When the user inputs in Chinese:
  1. First translate the user's message into English and show it to the user.
  2. Then continue executing the task in English.
- When the user inputs in English:
  1. First point out any grammatical errors or non-idiomatic expressions, and provide suggestions for correction.
  2. Then continue executing the task.
- The above "translation/proofreading" step is a mandatory prerequisite for every conversation, unless the user explicitly says "skip".
