# Project workflow

Each completed user-facing update must be published as a new GitHub Release with a new version, release notes, source tag and tested build assets, unless the user explicitly requests no new version or release for that update. Group fixes requested for the same update into one release. Never overwrite a published release tag or its assets to represent a new update.

Keep the Maven version, frontend package version, Docker JAR path, health response, server release download script and update health check consistent. Add release notes under docs/releases/<tag>.md. Validate the change and publish through .github/workflows/release.yml after creating its draft release. Confirm successful publication and asset upload before reporting completion.

Financial planning, credentials and private server access details must stay out of this repository. Preserve deployment .env and persistent data volumes during updates. Legal explanations requested privately in chat must not be published as site policies without a separate request.

Do not publish the owner's real name or other personal details in documentation, descriptions, screenshots or release notes. Do not add statements crediting AI for creating this project. Keep necessary technical identifiers such as the existing repository URL.

Public branding is pollyDrop, with an italic p in the existing frosted-glass style. The production domain is drop.pollybich.ru. Do not reintroduce retired branding in current files or artifacts. Keep Russian and English interfaces complete; country-based default language must use local IP lookup, with a stored manual choice taking precedence. Verify served frontend bytes before reporting a successful server update. Preserve encryption keys, transfer deadlines and consumed download counters during installation migration.
