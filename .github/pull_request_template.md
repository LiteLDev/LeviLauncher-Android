<!--Keep this PR short and clear. Fill only what is relevant. Remove sections that do not apply.
Replace [ ] with [x] only for checks you actually completed. Do not claim validation that was not performed.
For a bug fix, feature, documentation, translation, compatibility, or performance PR,
you may use the corresponding specialized template in .github/PULL_REQUEST_TEMPLATE/.-->

## Summary

<!-- Few-line description of what this PR changes. -->

## Related Issue

<!-- Use `Fixes #123`, `Closes #123`, or `Resolves #123` when applicable. -->

## Changes

<!-- List the main files or changes in simple bullets. -->

*

## Why

<!-- Explain why this change is needed. -->

## Validation

- [ ] `./gradlew assembleDebug` (or `:app:compileDebugKotlin` when run without Firebase/CurseForge secrets)
- [ ] `git diff --check`
- [ ] Tested on a device or emulator when the change affects launching, downloads, mod/skin management, or UI behavior

## Android & Minecraft Compatibility Impact

<!-- Describe which Android versions, device architectures, or Minecraft Bedrock versions are affected.
Write `None` when not applicable. -->

## Notes

<!-- Add any extra context, limitations, or follow-up points. -->

*

## Checklist

- [ ] The change is focused and contains no unrelated refactoring.
- [ ] Changed code has been formatted with the repository configuration.
- [ ] Translation keys remain aligned across supported languages where applicable.
- [ ] New third-party code or assets include the required license notice.
- [ ] Logs, screenshots, and test data contain no private information.