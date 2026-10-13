# Project workflow

Each completed user-facing update must be published as a new GitHub Release with a new version, release notes, source tag and tested build assets, unless the user explicitly asks to keep that update unpublished. Group fixes requested for the same update into one release. Never overwrite a published release tag or its assets to represent a new update.

Keep the Maven version, frontend package version, Docker JAR path, health response, server release download script and update health check consistent. Add release notes under docs/releases/<tag>.md. Validate the change and publish through .github/workflows/release.yml after creating its draft release. Confirm successful publication and asset upload before reporting completion.

Financial planning, credentials and private server access details must stay out of this repository. Preserve deployment .env and persistent data volumes during updates. Legal explanations requested privately in chat must not be published as site policies without a separate request.
