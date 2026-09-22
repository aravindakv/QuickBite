# QuickBite Agent Guidance

## Editor Formatting

- Treat `formatOnPaste` as a VS Code editor preference, not as a project formatter.
- Preserve the user's existing formatter and `editor.formatOnPaste` settings when editing `.vscode/settings.json`.
- Do not add or assume Spotless, ktlint, Detekt, Checkstyle, or another formatter unless the build explicitly introduces it.
- When a formatter is introduced, update the workspace settings and document the formatter's command in the same change.

## Build and Validation

- Use the Gradle wrapper (`./gradlew`) for project commands.
- Check the effective project/task structure with `./gradlew tasks --all --no-daemon` before relying on a task that may not exist yet.
- Java conventions are centralized in [build-logic/src/main/kotlin/quickbite.java-conventions.gradle.kts](build-logic/src/main/kotlin/quickbite.java-conventions.gradle.kts); keep Java toolchain and compiler/test settings there rather than duplicating them in services.
- Module membership is defined in [settings.gradle.kts](settings.gradle.kts), and dependency versions belong in [gradle/libs.versions.toml](gradle/libs.versions.toml).

## Repository Conventions

- Keep line-ending behavior consistent with [.gitattributes](.gitattributes): shell scripts use LF, Windows batch files use CRLF, and JARs are binary.
- Keep changes scoped to the owning module and avoid introducing project-wide tooling without a corresponding build configuration and validation command.