# Releasing

Releases use [Semantic Versioning](https://semver.org/).

- Merging a PR into `main` runs the *Auto Tag* workflow, which creates the next `vX.Y.Z` tag from the prefix of the PR title: `major: ...` bumps X, `minor: ...` bumps Y, `patch: ...` bumps Z. Any other title creates no tag and no release. After a tag is created it starts the *Release APK* workflow.

## Configure CI signing

Install the JDK (`keytool`) and GitHub CLI (`gh`), and authenticate with `gh auth login` using an account with repository administration access. From the repository root, run in Bash (including Git Bash on Windows):

```bash
bash .github/scripts/setup-release-secrets.sh
```

Enter one password of at least 6 characters at the hidden prompt. The [setup script](../.github/scripts/setup-release-secrets.sh) detects the repository from the Git `origin` remote using `gh` (not the upstream or CLI default repository), derives `KEY_ALIAS` from its lowercase name (`BibleAudioProducer` becomes `bibleaudioproducer`), and creates `../bibleaudioproducer-release.keystore` in PKCS12 format if it does not exist. It uses the same password for `KEYSTORE_PASSWORD` and `KEY_PASSWORD`, generates `KEYSTORE_BASE64` directly into the upload without a temporary Base64 file, and sets all four secrets in the tag-only `release` environment. Passwords are not passed as command-line arguments.

By default, an existing local keystore is reused, not replaced; it must contain the derived alias and use the same password for the keystore and key. Existing JKS keystores do not need conversion. To choose another repository or an existing keystore path:

```bash
bash .github/scripts/setup-release-secrets.sh danzuep/BibleAudioProducer ../bibleaudioproducer-release.keystore
```

To deliberately generate a new signing key and overwrite the local keystore and all four CI secrets, add `--overwrite`:

```bash
bash .github/scripts/setup-release-secrets.sh --overwrite
```

The flag also works with explicit repository and keystore arguments. Back up the original keystore first. The local file is replaced only after key generation succeeds, but CI uploads are not transactional: if an upload fails, rerun without `--overwrite` to upload the same new key.

For a published app, always reuse its original signing key: a new key cannot sign updates to existing installations. If CI already has `KEYSTORE_BASE64` but the local keystore is missing, the script refuses to generate a replacement unless `--overwrite` is explicitly supplied. Keep the keystore and password securely backed up; do not commit or share them.

Branch runs of `release.yml` use the secret-less `dry-run` environment. Without a keystore the release APK is signed with the debug key (installable, but not suitable for updates from a different key).

More about app signing can be found [here](https://developer.android.com/studio/publish/app-signing).
