# Agent guidance

## Pull requests and releases

- For PRs targeting `main`, prefix the title with `major:`, `minor:`, or `patch:` to request a semantic-version release: breaking changes, backward-compatible features, or backward-compatible fixes, respectively.
- Merging a prefixed PR creates the next `vX.Y.Z` tag and starts the APK release workflow. A title without one of these prefixes creates no tag or release.
- With no existing version tags, a `major:` PR starts releases at `v1.0.0`.
- If release signing secrets are absent, APKs use a temporary key and the release is a prerelease. Such APKs cannot upgrade installations signed with another key.
