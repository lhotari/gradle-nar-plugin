package de.fanero.gradle.plugin.nar

import org.gradle.testkit.runner.GradleRunner
import spock.lang.Specification
import spock.lang.TempDir

import java.nio.file.Path
import java.util.jar.Manifest
import java.util.regex.Pattern
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

/**
 * @author Robert Kühne
 */
class NarPluginFuncTest extends Specification {

    private static final TEST_BASE_NAME = 'nar-test'
    private static final TEST_VERSION = '1.0'

    @TempDir
    Path testProjectDir

    File buildFile
    File settingsFile

    def setup() {
        buildFile = testProjectDir.resolve('build.gradle').toFile()
        buildFile << """
plugins {
    id 'io.github.lhotari.gradle-nar-plugin'
}
nar {
    baseName '${TEST_BASE_NAME}'
}
group = 'de.fanero.test'
version = '${TEST_VERSION}'
"""
        settingsFile = testProjectDir.resolve('settings.gradle').toFile()
        settingsFile << """
rootProject.name = "nar-test"
"""
    }

    def "test simple nar"() {

        when:
        GradleRunner.create()
                .withProjectDir(testProjectDir.toFile())
                .withArguments('nar')
                .withPluginClasspath()
                .build()

        Manifest manifest = extractManifest()

        then:
        manifest != null
        manifest.getMainAttributes().getValue('Nar-Group') == 'de.fanero.test'
        manifest.getMainAttributes().getValue('Nar-Id') == 'nar-test'
        manifest.getMainAttributes().getValue('Nar-Version') == '1.0'
        manifest.getMainAttributes().getValue('Nar-Dependency-Id') == null
    }

    def "should include pulsar-io.yaml"() {
        when:
        def pulsarIoYamlFile = testProjectDir.resolve('src/main/resources/META-INF/services/pulsar-io.yaml').toFile()
        pulsarIoYamlFile.parentFile.mkdirs()
        def pulsarIoYamlContent = '''
name: connector name
description: connector description
sourceClass: fully qualified class name (only if source connector)
sinkClass: fully qualified class name (only if sink connector)
'''
        pulsarIoYamlFile.text = pulsarIoYamlContent

        GradleRunner.create()
                .withProjectDir(testProjectDir.toFile())
                .withArguments('nar')
                .withPluginClasspath()
                .build()

        def pulsarIoYamlContentPackaged
        eachZipEntry { ZipInputStream zip, ZipEntry entry ->
            println entry.name
            if (entry.name == 'META-INF/services/pulsar-io.yaml') {
                pulsarIoYamlContentPackaged = zip.text
                return false
            } else {
                return true
            }
        }

        then:
        pulsarIoYamlContentPackaged != null
        pulsarIoYamlContentPackaged == pulsarIoYamlContent
    }

    def "test parent nar entry"() {

        buildFile << """
repositories {
    mavenCentral()
}
dependencies {
    nar 'org.apache.nifi:nifi-standard-services-api-nar:0.2.1'
}
"""
        when:
        GradleRunner.create()
                .withProjectDir(testProjectDir.toFile())
                .withArguments('nar')
                .withPluginClasspath()
                .build()

        Manifest manifest = extractManifest()

        then:
        manifest != null
        manifest.getMainAttributes().getValue('Nar-Group') == 'de.fanero.test'
        manifest.getMainAttributes().getValue('Nar-Id') == 'nar-test'
        manifest.getMainAttributes().getValue('Nar-Version') == '1.0'
        manifest.getMainAttributes().getValue('Nar-Dependency-Group') == 'org.apache.nifi'
        manifest.getMainAttributes().getValue('Nar-Dependency-Id') == 'nifi-standard-services-api-nar'
        manifest.getMainAttributes().getValue('Nar-Dependency-Version') == '0.2.1'
    }

    def "test multiple parent nar entries"() {

        buildFile << """
repositories {
    mavenCentral()
}
dependencies {
    nar 'org.apache.nifi:nifi-standard-services-api-nar:0.2.1'
    nar 'org.apache.nifi:nifi-enrich-nar:1.5.0'
}
"""
        expect:
        GradleRunner.create()
                .withProjectDir(testProjectDir.toFile())
                .withArguments('nar')
                .withPluginClasspath()
                .buildAndFail()
    }

    def "test bundled jar dependencies"() {

        buildFile << """
repositories {
    mavenCentral()
}
dependencies {
    implementation group: 'commons-io', name: 'commons-io', version: '2.11.0'
}
"""
        when:
        GradleRunner.create()
                .withProjectDir(testProjectDir.toFile())
                .withArguments('nar')
                .withPluginClasspath()
                .build()

        then:
        countBundledJars() == 2
    }

    def "test override of manifest configuration"() {

        buildFile << """
repositories {
    mavenCentral()
}
dependencies {
    nar 'org.apache.nifi:nifi-standard-services-api-nar:0.2.1'
}
nar {
    manifest {
        attributes 'Nar-Group': 'group-override', 'Nar-Id': 'id-override', 'Nar-Version': 'version-override'
        attributes 'Nar-Dependency-Group': 'Nar-Dependency-Group-override', 'Nar-Dependency-Id': 'Nar-Dependency-Id-override', 'Nar-Dependency-Version': 'Nar-Dependency-Version-override'
    }
}
"""
        when:
        GradleRunner.create()
                .withProjectDir(testProjectDir.toFile())
                .withArguments('nar')
                .withPluginClasspath()
                .build()

        Manifest manifest = extractManifest()

        then:
        manifest.getMainAttributes().getValue('Nar-Group') == 'group-override'
        manifest.getMainAttributes().getValue('Nar-Id') == 'id-override'
        manifest.getMainAttributes().getValue('Nar-Version') == 'version-override'
        manifest.getMainAttributes().getValue('Nar-Dependency-Group') == 'Nar-Dependency-Group-override'
        manifest.getMainAttributes().getValue('Nar-Dependency-Id') == 'Nar-Dependency-Id-override'
        manifest.getMainAttributes().getValue('Nar-Dependency-Version') == 'Nar-Dependency-Version-override'
    }

    def "test override bundled dependencies"() {
        buildFile << """
nar {
    bundledDependencies = [jar]
}
"""
        when:
        GradleRunner.create()
                .withProjectDir(testProjectDir.toFile())
                .withArguments('nar')
                .withPluginClasspath()
                .build()

        then:
        countBundledJars() == 1
    }

    def "test empty bundled dependencies"() {
        buildFile << """
nar {
    bundledDependencies = null
}
"""
        when:
        GradleRunner.create()
                .withProjectDir(testProjectDir.toFile())
                .withArguments('nar')
                .withPluginClasspath()
                .build()

        then:
        countBundledJars() == 0
    }

    def "test remove parent configuration"() {
        buildFile << """
nar {
    parentNarConfiguration = null
}
"""
        when:
        GradleRunner.create()
                .withProjectDir(testProjectDir.toFile())
                .withArguments('nar')
                .withPluginClasspath()
                .build()

        Manifest manifest = extractManifest()

        then:
        countBundledJars() == 1
        manifest.getMainAttributes().getValue('Nar-Dependency-Group') == null
        manifest.getMainAttributes().getValue('Nar-Dependency-Id') == null
        manifest.getMainAttributes().getValue('Nar-Dependency-Version') == null
    }

    int countBundledJars() {
        int counter = 0
        Pattern pattern = Pattern.compile('^META-INF/bundled-dependencies/.+$')
        eachZipEntry { ZipInputStream zip, ZipEntry entry ->
            if (pattern.matcher(entry.name).matches()) {
                println entry.name
                counter++
            }
            true
        }
        counter
    }

    Manifest extractManifest() {
        Manifest manifest = null
        eachZipEntry { ZipInputStream zip, ZipEntry entry ->
            if (entry.name == 'META-INF/MANIFEST.MF') {
                manifest = new Manifest(zip)
                return false
            } else {
                return true
            }
        }

        manifest
    }

    private void eachZipEntry(Closure closure) {
        narFile().withInputStream {
            ZipInputStream zip = new ZipInputStream(it)
            ZipEntry entry = zip.nextEntry
            while (entry != null) {
                def result = closure(zip, entry)
                if (!result) {
                    break
                }
                entry = zip.nextEntry
            }
        }
    }

    private File narFile() {
        new File(testProjectDir.toFile(), "build/libs/${TEST_BASE_NAME}-${TEST_VERSION}.nar")
    }
}
