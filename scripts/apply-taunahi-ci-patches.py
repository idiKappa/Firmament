#!/usr/bin/env python3
from pathlib import Path


def replace_once(path: Path, old: str, new: str, label: str) -> None:
    text = path.read_text(encoding="utf-8")
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected exactly one match in {path}, got {count}")
    path.write_text(text.replace(old, new, 1), encoding="utf-8")
    print(f"patched {label}: {path}")


build = Path("build.gradle.kts")
replace_once(
    build,
    'import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar\n',
    'import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar\nimport proguard.gradle.ProGuardTask\n',
    "ProGuard import",
)

old_assemble = 'tasks.assemble { dependsOn(shadowJar) }\n'
proguard_task = r'''val obfuscateJar by tasks.registering(ProGuardTask::class) {
	dependsOn(shadowJar)

	injars(shadowJar.flatMap { it.archiveFile })

	// Resolve JDK libraries from the configured Java 25 toolchain. ProGuard needs
	// the JMODs to resolve the complete class hierarchy.
	val jdkHome = javaToolchains.launcherFor(java.toolchain)
		.map { it.metadata.installationPath.asFile }

	doFirst {
		configurations.compileClasspath.get().forEach { libraryjars(it) }
		val jmods = File(jdkHome.get(), "jmods")
		val jmodFiles = jmods.listFiles().orEmpty().filter { it.extension == "jmod" }
		if (jmodFiles.isEmpty())
			error(
				"No .jmod files in $jmods. ProGuard needs a JDK distribution that ships JMODs " +
					"(Zulu, Corretto, Oracle OpenJDK)."
			)
		jmodFiles.forEach { libraryjars(mapOf("jarfilter" to "!**.jar,!module-info.class"), it) }
	}

	// ProGuard cannot use the same path for input and output. shadowJar writes
	// Firmament-${version}.jar, so keep the obfuscated artifact separate.
	outjars(layout.buildDirectory.file("libs/${base.archivesName.get()}-${version}-obfuscated.jar"))
	configuration(project.file("proguard.pro"))
}

tasks.assemble {
	dependsOn(shadowJar)
	dependsOn(obfuscateJar)
}
'''
replace_once(build, old_assemble, proguard_task, "ProGuard task")

textures = Path("src/texturePacks/java/moe/nea/firmament/features/texturepack/CustomBlockTextures.kt")
needle = '''\tval insideFallbackCall = ThreadLocal.withInitial { 0 }\n\n\t@JvmStatic\n\tfun enterFallbackCall() {'''
replacement = '''\tval insideFallbackCall = ThreadLocal.withInitial { 0 }\n\n\tinit {\n\t\tmoe.nea.firmament.init.BlockRenderHooks.enterCallback = Runnable { enterFallbackCall() }\n\t\tmoe.nea.firmament.init.BlockRenderHooks.exitCallback = Runnable { exitFallbackCall() }\n\t\tmoe.nea.firmament.init.BlockRenderHooks.patchCallback =\n\t\t\tmoe.nea.firmament.init.BlockRenderHooks.PatchCallback { model, pos, state -> patchIndigo(model, pos, state) }\n\t}\n\n\t@JvmStatic\n\tfun enterFallbackCall() {'''
replace_once(textures, needle, replacement, "block texture ProGuard bridge")

print("Taunahi mc26.2 CI patches applied successfully")
