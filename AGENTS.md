# Repository instructions

- Before starting a new feature, inspect the worktree and create or switch to a focused branch
  named `feat/<short-kebab-topic>`. Never implement feature work directly on `main`.
- Preserve unrelated and pre-existing worktree changes. Do not rewrite or discard them.
- Keep commits and branches focused on one concern.
- Before handing off code changes, run:

  ```bash
  ./gradlew testDebugUnitTest lintDebug assembleDebug
  ```

- Treat receipt images, AI output, promotion pages, and inferred prices as untrusted input.
- Keep all monetary arithmetic in deterministic domain code using integer minor units; AI can
  extract facts and propose candidates, but it cannot author final ledger values.
