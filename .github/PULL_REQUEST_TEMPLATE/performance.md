<!--Describe the performance-related change. Remove sections that do not apply.
Replace [ ] with [x] only for checks you actually completed. Do not claim validation or measurements that were not performed.-->

## Summary

<!-- Briefly describe what was optimized and where it affects the launcher (startup, downloads, memory, UI smoothness). -->

## Related Issue

<!-- Use `Fixes #123`, `Closes #123`, or `Resolves #123` when applicable. -->

## Changes

*

## Why It Improves Performance

<!--
Explain what unnecessary work, overhead, contention, allocation, I/O,
memory usage, or other bottleneck is reduced or avoided.
-->

## Benchmark

<!--
Optional.

If benchmark or profiling data is available, describe:
- what was measured;
- the workload or scenario;
- relevant environment and versions;
- before/after results;
- how the measurement was obtained.

Do not include benchmark data that was not actually measured.
-->

- [ ] Benchmark or profiling data provided

## Validation

- [ ] `./gradlew assembleDebug` (or `:app:compileDebugKotlin` when run without Firebase/CurseForge secrets)
- [ ] `git diff --check`
- [ ] Tested the affected behavior on a device or emulator when applicable

<!--
Describe additional validation and include relevant device, Android version,
and app version when runtime behavior is affected.
-->

## Android & Minecraft Compatibility Impact

<!-- Describe which Android versions, device architectures, or Minecraft Bedrock versions are affected.
Write `None` when not applicable. -->

## Notes

<!-- Add limitations, trade-offs, profiling details, or follow-up work when useful. -->

## Checklist

- [ ] The change is focused and contains no unrelated refactoring.
- [ ] Changed code has been formatted with the repository configuration.
- [ ] New third-party code or assets include the required license notice.
- [ ] Logs, screenshots, and test data contain no private information.