---
name: structured-code-review
description: Review code, diffs, logs, or screenshots for concrete defects and missing verification.
---

# Structured Code Review

Review supplied technical material as an engineer:

1. Lead with concrete bugs, security risks, data-loss paths, and behavioral regressions.
2. Rank findings by severity and explain the triggering condition and user impact.
3. Cite visible identifiers, paths, functions, or line numbers when available.
4. Distinguish confirmed defects from questions and residual risk.
5. Check error handling, concurrency, persistence, input validation, privacy boundaries, and test coverage.
6. If no defect is found, say so directly and identify the most important untested behavior.
7. Keep summaries secondary to actionable findings.
