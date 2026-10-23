# Releasing designguard

Releases go to Maven Central as `io.github.yoavgeva:designguard`, through the Sonatype Central Portal. Pushing a tag
like `v0.1.0` runs [the release workflow](../.github/workflows/release.yml). It runs the whole build, signs the jar,
sources and javadoc, and uploads them. The upload waits until Central has validated it; publishing it is a click in
the portal.

## One-time setup

1. **Namespace.** Sign in at [central.sonatype.com](https://central.sonatype.com) with the GitHub account
   `yoavgeva`. That verifies the namespace `io.github.yoavgeva`.
2. **Portal token.** In the portal, choose *View Account* → *Generate User Token*.
3. **Signing key.** Create a key with a passphrase, and publish its public half so Central can check signatures:

   ```sh
   gpg --quick-generate-key "Yoav Geva <address>" ed25519 sign 3y
   gpg --keyserver keyserver.ubuntu.com --send-keys <key id>
   gpg --armor --export-secret-keys <key id>     # the value of MAVEN_GPG_PRIVATE_KEY
   ```

4. **Secrets.** In the GitHub repository, create the environment `maven-central`, under *Settings* → *Environments*.
   Optionally add yourself as a required reviewer, to approve each release. Give it four secrets:

   | Secret | Value |
   |---|---|
   | `CENTRAL_TOKEN_USERNAME` | the token's username |
   | `CENTRAL_TOKEN_PASSWORD` | the token's password |
   | `MAVEN_GPG_PRIVATE_KEY` | the armored private key |
   | `MAVEN_GPG_PASSPHRASE` | its passphrase |

## Each release

1. Set the version, without `-SNAPSHOT`, and update the README's install snippet:

   ```sh
   mise exec -- mvn versions:set -DnewVersion=0.1.0 -DgenerateBackupPoms=false
   ```

2. Merge that through a pull request, then tag the merge commit and push the tag:

   ```sh
   git tag v0.1.0 && git push origin v0.1.0
   ```

   The workflow fails if the tag doesn't match the version in `pom.xml`.
3. When the workflow is green, open *Deployments* in the portal and publish the validated upload. It reaches Maven
   Central within about half an hour.
4. Set the next snapshot version, e.g. `0.2.0-SNAPSHOT`, in another pull request.

To build the release artifacts locally without signing: `mise exec -- mvn -P release -Dgpg.skip verify`.
