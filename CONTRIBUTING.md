# Contributing

## Branch workflow

Do not develop new functionality directly on `main`.

1. Synchronize and inspect the base branch before starting work.
2. Create a focused branch named `feat/<short-kebab-topic>` for each feature.
3. Keep unrelated changes out of the branch and preserve any existing worktree changes.
4. Run the project quality gate before merging:

   ```bash
   ./gradlew testDebugUnitTest lintDebug assembleDebug
   ```

5. Merge only after the branch is reviewable and the checks pass.

Use `fix/<short-kebab-topic>` for isolated corrections and `docs/<short-kebab-topic>` for
documentation-only work. The default for product development in this repository is `feat/`.
