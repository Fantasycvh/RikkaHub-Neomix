"""Build a debug-only, same-package media acceptance host for OEM instrumentation failures.

Uses the existing backupverification application ID. Generated sources/manifest/init script
stay under build/; neither production source sets nor release dependencies are modified.
Run with JAVA_HOME and GRADLE_USER_HOME set for the local Android build environment.
"""

from pathlib import Path
import shutil
import subprocess


def main():
    root = Path(__file__).resolve().parents[2]
    scratch = root / "build/media-chat-host"
    source = scratch / "src"
    source.mkdir(parents=True, exist_ok=True)
    for relative in (
        "app/androidrik/rikkahub/data/task/MediaGenerationAcceptanceTest.kt",
        "app/androidrik/rikkahub/data/repository/MemoryIsolationTestRunner.kt",
    ):
        target = source / relative
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(root / "app/src/androidTest/java" / relative, target)
    manifest = (root / "app/src/debug/AndroidManifest.xml").read_text(encoding="utf-8")
    manifest = manifest.replace("</manifest>", '''
    <instrumentation android:name="app.androidrik.rikkahub.data.repository.MemoryIsolationTestRunner"
        android:targetPackage="app.androidrik.rikkahub.backupverification.debug" />
</manifest>''')
    (scratch / "AndroidManifest.xml").write_text(manifest, encoding="utf-8")
    init = r'''
gradle.beforeProject { project ->
    if (project.name == 'app') {
        project.plugins.withId('com.android.application') {
            project.android.sourceSets.getByName('debug').java.srcDir('@SOURCE@')
            project.android.sourceSets.getByName('debug').kotlin.srcDir('@SOURCE@')
            project.android.sourceSets.getByName('debug').manifest.srcFile('@MANIFEST@')
            project.android.sourceSets.getByName('debug').assets.srcDir('@SCHEMAS@')
            def catalog = project.extensions.getByType(org.gradle.api.artifacts.VersionCatalogsExtension).named('libs')
            ['androidx-junit', 'androidx-espresso-core', 'androidx-room-testing'].each { alias ->
                project.dependencies.add('debugImplementation', catalog.findLibrary(alias).get())
            }
        }
    }
}
'''.replace("@SOURCE@", source.as_posix()).replace("@MANIFEST@", (scratch / "AndroidManifest.xml").as_posix()).replace("@SCHEMAS@", (root / "app/schemas").as_posix())
    script = scratch / "host.init.gradle"
    script.write_text(init, encoding="utf-8")
    command = [str(root / "gradlew.bat"), ":app:assembleDebug", "-PbackupIsolatedTests=true",
               "--init-script", str(script), "--no-configuration-cache", "--offline", "--max-workers=2", "-q"]
    subprocess.run(command, cwd=root, check=True)


if __name__ == "__main__":
    main()
