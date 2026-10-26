# Releasing designguard

Releases go to Maven Central as `io.github.yoavgeva:designguard`, through the Sonatype Central Portal. One run of
[the release workflow](../.github/workflows/release.yml) does a whole release, with nothing to do by hand.

## One-time setup

1. **Namespace.** Sign in at [central.sonatype.com](https://central.sonatype.com) with the GitHub account
   `yoavgeva`. That verifies the namespace `io.github.yoavgeva`.
2. **Portal token.** In the portal, choose *View Account* → *Generate User Token*.
3. **Signing key.** Create a key with a passphrase, and publish its public half so Central can check signatures:

   ```sh
   gpg --quick-generate-key "designguard Releases" rsa4096 sign 3y
   gpg --keyserver keyserver.ubuntu.com --send-keys <key id>
   gpg --armor --export-secret-keys <key id>     # the value of MAVEN_GPG_PRIVATE_KEY
   ```

   The key's name is public on the keyserver, so it names no email address.

4. **Secrets.** In the GitHub repository, create the environment `maven-central`, under *Settings* → *Environments*.
   Optionally add yourself as a required reviewer, to approve each release. Give it four secrets:

   | Secret | Value |
   |---|---|
   | `CENTRAL_TOKEN_USERNAME` | the token's username |
   | `CENTRAL_TOKEN_PASSWORD` | the token's password |
   | `MAVEN_GPG_PRIVATE_KEY` | the armored private key |
   | `MAVEN_GPG_PASSPHRASE` | its passphrase |

## Each release

Run the release workflow: in GitHub, *Actions* → *Release* → *Run workflow*, or

```sh
gh workflow run release.yml
```

It releases the version in `pom.xml` without `-SNAPSHOT`, such as `0.2.0` from `0.2.0-SNAPSHOT`.
`gh workflow run release.yml -f version=1.0.0` releases another. In one run, it:

1. commits the release, which sets the version in `pom.xml` and the README's install snippets, and tags it `v0.2.0`;
2. commits the next snapshot, `0.3.0-SNAPSHOT`, and runs CI on it on Java 17, 21, 25 and 27, since `main` takes
   only commits CI has passed;
3. builds the release commit again, tests and checks included, signs it, publishes it to Maven Central and waits
   until Central has it, which can take half an hour or more;
4. pushes the tag, creates its GitHub release with notes from the merged pull requests, and moves `main` to the two
   commits.

Nothing is published if the version is already released, CI fails or Central rejects the upload. If `main` moved
during the run, the release is out and tagged, and the run says to merge its `release/v…` branch into `main`.

To build the release artifacts locally without signing: `mise exec -- mvn -P release -Dgpg.skip verify`.
