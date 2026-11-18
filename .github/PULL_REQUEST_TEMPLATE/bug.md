<!--Describe what was fixed and how it was verified. Remove sections that do not apply.
Replace [ ] with [x] only for checks you actually completed. Do not claim validation that was not performed.-->

## Summary

<!-- Briefly describe the bug and the fix. -->

## Related Issue

<!-- Use `Fixes #123`, `Closes #123`, or `Resolves #123` when applicable. -->

## Root Cause

<!-- What caused the bug? -->

## Solution

<!-- How does this PR fix the problem? -->

## Validation

- [ ] `./gradlew assembleDebug` (or `:app:compileDebugKotlin` when run without Firebase/CurseForge secrets)
- [ ] `git diff --check`
- [ ] Reproduced the bug before the fix when practical
- [ ] Verified the affected behavior after the fix
- [ ] Tested on a device or emulator when the change affects launching, downloads, mod/skin management, or UI behavior

## Android & Minecraft Compatibility Impact

<!-- Describe which Android versions, device architectures, or Minecraft Bedrock versions are affected.
Write `None` when not applicable. -->

## Notes

<!-- Additional context, screenshots, logs, or limitations (if any). -->

## Checklist

- [ ] The change is focused and contains no unrelated refactoring.
- [ ] Changed code has been formatted with the repository configuration.
- [ ] Translation keys remain aligned across supported languages where applicable.
- [ ] New third-party code or assets include the required license notice.
- [ ] Logs, screenshots, and test data contain no private information.