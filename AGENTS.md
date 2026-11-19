# Agent guidance

## Pull requests and releases

- For PRs targeting `main`, prefix the title with `major:`, `minor:`, or `patch:` to request a semantic-version release: breaking changes, backward-compatible features, or backward-compatible fixes, respectively.
- Merging a prefixed PR creates the next `vX.Y.Z` tag and starts the APK release workflow. A title without one of these prefixes creates no tag or release.
- With no existing version tags, a `major:` PR starts releases at `v1.0.0`.
- If release signing secrets are absent, APKs use a temporary key and the release is a prerelease. Such APKs cannot upgrade installations signed with another key.

## Testing with GitHub Actions

- The sandbox usually cannot run Gradle, so validate with Actions: push the branch, then `gh workflow run android.yml --ref <branch>` and watch it with `gh run view <run-id>`. If it asks for permission to run, retrying once may work around it.
- The `build` job runs `:core:jvmTest` and `:app:testDebugUnitTest`. Once it passes, open the pull request (see the release title prefixes above) without waiting for the slower emulator `launch` jobs.
