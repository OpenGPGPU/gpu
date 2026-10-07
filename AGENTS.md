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

# Cloud Agent development

Functional work is host-side Chisel. The image provides JDK 21, sbt 1.10.7 (`project/build.properties`), Verilator 5.050 on `/usr/local`, and the chipsalliance espresso 2.4 binary. Do not substitute the distro Verilator: 5.020 is too old, and 5.032 faults inside `GraphicsAddressTranslator`.

- `sbt compile`
- `sbt 'set Test / parallelExecution := false' "testOnly <suite>"` — run suites serially; forked parallelism exhausts memory on a 16 GB agent
- `python3 scripts/test_driver.py` — kernel-free driver tests
- `python3 scripts/test_test_selection.py`

Use `JAVA_OPTS` and `JVM_OPTS` of `-Xms1G -Xmx3G -Xss4M` for a single suite. Compiled Verilator models are reused from `target/chiselsim`. `git submodule update --init --recursive` fetches the ASAP7 SRAM views used by physical-design scripts. The full `sbt test` run and `scripts/qualify_functional.sh` guest gate need a larger machine and the sibling ARTI checkout; they are outside the boot path.
