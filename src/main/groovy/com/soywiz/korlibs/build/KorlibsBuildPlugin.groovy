package com.soywiz.korlibs.build

import org.apache.tools.ant.taskdefs.condition.Os
import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.artifacts.ProjectDependency
import org.gradle.api.artifacts.maven.MavenDeployment
import org.gradle.api.publish.maven.MavenPublication
import org.gradle.api.tasks.Copy
import org.gradle.api.tasks.bundling.Jar

class KorlibsBuildPlugin implements Plugin<Project> {
    @Override
    void apply(Project rootProject) {
        /*
        repositories {
            jcenter()
            maven { url "https://plugins.gradle.org/m2/" }
            mavenLocal()
        }

        dependencies {
            classpath "org.jetbrains.kotlin:kotlin-gradle-plugin:$kotlinVersion"
        }
        */

        rootProject.configurations {
            provided
        }

        rootProject.allprojects {
            repositories {
                mavenLocal()
                jcenter()
                mavenLocal()
            }

            group projectGroup
            version projectVersion

            apply plugin: 'java'
            apply plugin: 'maven'
            apply plugin: 'signing'
            apply plugin: 'maven-publish'
            apply plugin: 'idea'

            if (project == rootProject) {
                apply plugin: 'kotlin'
            }

            it.afterEvaluate {
                if (it.plugins.hasPlugin("kotlin-platform-common")) {
                    dependencies {
                        compile "org.jetbrains.kotlin:kotlin-stdlib-common:$kotlinVersion"
                        testCompile "org.jetbrains.kotlin:kotlin-test-common:$kotlinVersion"
                        testCompile "org.jetbrains.kotlin:kotlin-test-annotations-common:$kotlinVersion"
                    }

                    kotlin {
                        experimental { coroutines 'enable' }
                    }
                }
                if (it.plugins.hasPlugin("kotlin-platform-jvm") || it.plugins.hasPlugin("kotlin")) {
                    dependencies {
                        compile "org.jetbrains.kotlin:kotlin-stdlib:$kotlinVersion"
                        testCompile "org.jetbrains.kotlin:kotlin-test:$kotlinVersion"
                        testCompile "org.jetbrains.kotlin:kotlin-test-junit:$kotlinVersion"
                        testCompile "junit:junit:4.12"
                    }

                    kotlin {
                        experimental { coroutines 'enable' }
                    }

                    compileJava.options.encoding = 'UTF-8'
                    compileTestJava.options.encoding = 'UTF-8'

                    sourceCompatibility = 1.7
                    targetCompatibility = 1.7
                }

                // https://discuss.kotlinlang.org/t/unit-testing-in-kotlin-js/3943
                // https://github.com/JetBrains/kotlin-examples/blob/5e883a6d67afc8b8aeb8991af6a7b6183be2213f/gradle/js-tests/mocha/build.gradle
                if (it.plugins.hasPlugin("kotlin-platform-js") || it.plugins.hasPlugin("kotlin2js")) {
                    dependencies {
                        compile "org.jetbrains.kotlin:kotlin-stdlib-js:$kotlinVersion"
                        testCompile "org.jetbrains.kotlin:kotlin-test-js:$kotlinVersion"
                    }

                    kotlin {
                        experimental { coroutines 'enable' }
                    }

                    [compileKotlin2Js, compileTestKotlin2Js]*.configure {
                        kotlinOptions.moduleKind = "umd"
                        kotlinOptions.sourceMap = true
                    }

                    project.task(type: Copy, dependsOn: compileKotlin2Js, 'populateNodeModules') {
                        from compileKotlin2Js.destinationDir

                        configurations.testCompile.each {
                            from zipTree(it.absolutePath).matching { include '*.js' }
                        }

                        into "${buildDir}/node_modules"
                    }

                    project.task(type: Task, dependsOn: [compileTestKotlin2Js, populateNodeModules], 'fixJsForAsync') {
                        doLast {
                            def k2jsOutputFile = compileTestKotlin2Js.outputFile
                            File file = (k2jsOutputFile instanceof File) ? k2jsOutputFile : new File("$k2jsOutputFile")
                            if (file.exists()) {
                                File fileOut = new File(file.absolutePath + ".fix.js")
                                def timeout = 2000
                                fileOut.text = file.text.replaceAll(
                                        /(?m)(?s)test\('(.*?)', (false|true), function \(\) \{\s*(.*?);\s*\}\);/,
                                        'test("$1", $2, function() { this.timeout(' + timeout + '); global.testPromise = null; var res = $3 || (global.testPromise); return (res instanceof Promise) ? res : undefined; });'
                                )
                            }
                        }
                    }

                    project.task(type: Task, dependsOn: [fixJsForAsync], 'runMocha') {
                        doLast {
                            def k2jsOutputFile = compileTestKotlin2Js.outputFile
                            File fileOut = (k2jsOutputFile instanceof File) ? k2jsOutputFile : new File("$k2jsOutputFile")

                            if (fileOut.exists()) {
                                String[] cmd
                                if (Os.isFamily(Os.FAMILY_WINDOWS)) {
                                    cmd = ["cmd", "/c", "mocha.cmd" as String, fileOut]
                                } else {
                                    cmd = ["/bin/bash", '-c', "mocha '${fileOut}'"]
                                }

                                if (project.hasProperty('projectNodeModules')) {
                                    for (nodeModule in projectNodeModules) {
                                        if (!(new File("$buildDir/node_modules/$nodeModule")).exists()) {
                                            executeShell("npm link $nodeModule", buildDir)
                                        }
                                    }
                                }

                                ProcessBuilder pb = new ProcessBuilder(cmd as String[])
                                pb.environment().putAll(System.getenv())
                                pb.directory(new File("$buildDir/node_modules"))
                                def p = pb.start()
                                p.in.eachLine { System.out.println(it) }
                                p.err.eachLine { System.err.println(it) }
                                if (p.waitFor() != 0) {
                                    throw new GradleException('error occurred running ' + cmd)
                                }
                            }
                        }
                    }

                    test.dependsOn runMocha
                }
            }

            afterEvaluate {
                project.configure(project) {
                    task(type: Jar, 'javadocJar') {
                        classifier = 'javadoc'
                        from 'build/docs/javadoc'
                    }

                    task(type: Jar, 'sourcesJar') {
                        classifier = 'sources'
                        from sourceSets.main.allSource
                        if (project != rootProject) {
                            if (!plugins.hasPlugin("kotlin-platform-common")) {
                                ProjectDependency pd = (ProjectDependency) (configurations
                                        .findByName("expectedBy")?.dependencies
                                        ?.find { it instanceof ProjectDependency })
                                if (pd != null) {
                                    from pd.dependencyProject.sourceSets.main.allSource
                                }
                            }
                        }
                    }

                    artifacts {
                        archives javadocJar
                        archives sourcesJar
                    }
                }
            }

            if (project.hasProperty('sonatypeUsername')) {
                signing {
                    sign configurations.archives
                }

                uploadArchives {
                    repositories {
                        mavenDeployer {
                            beforeDeployment { MavenDeployment deployment -> signing.signPom(deployment) }

                            repository(url: "https://oss.sonatype.org/service/local/staging/deploy/maven2/") {
                                authentication(userName: project.sonatypeUsername, password: project.sonatypePassword)
                            }

                            pom.project {
                                name "${project.name}"
                                packaging 'jar'
                                description projectDesc
                                inceptionYear projectInceptionYear

                                if (projectHost == 'github') {
                                    url "https://github.com/$projectOrg/$projectName/"
                                    scm {
                                        url "scm:git@github.com:$projectOrg/${projectName}.git"
                                        connection "scm:git@github.com:$projectOrg/${projectName}.git"
                                        developerConnection "scm:git@github.com:$projectOrg/${projectName}.git"
                                    }
                                } else {
                                    throw new Exception("Unknown host $projectHost")
                                }

                                licenses {
                                    license {
                                        if (projectLicense == 'MIT') {
                                            name 'MIT License'
                                            url 'https://opensource.org/licenses/MIT'
                                        } else {
                                            throw new Exception("Unknown license $projectLicense")
                                        }
                                        distribution 'repo'
                                    }
                                }

                                developers {
                                    developer {
                                        id projectDevelNick
                                        name projectDevelName
                                    }
                                }
                            }
                        }
                    }
                }
            }

            publishing {
                publications {
                    MyPublication(MavenPublication) {
                        from components.java
                        groupId project.group
                        artifactId project.name
                        version "$project.version"
                    }
                }
            }

            task(dependsOn: ['install', 'uploadArchives'], 'deploy') {
            }
        }

        rootProject.configure(rootProject) {
            String pn = projectName
            if (!pn.contains("-")) {
                def projectGroupSlash = projectGroup.replace('.', '/')
                def capitalizedProjectName = capitalize(projectName)
                def upperProjectName = projectName.toUpperCase()
                def verFile
                if (new File(rootProject.rootDir, "$projectName/common").exists()) {
                    verFile = new File(rootProject.rootDir, "$projectName/common/src/main/kotlin/$projectGroupSlash/$projectName/${capitalizedProjectName}Version.kt")
                } else {
                    verFile = new File(rootProject.rootDir, "src/main/kotlin/$projectGroupSlash/$projectName/${capitalizedProjectName}Version.kt")
                }
                verFile.parentFile.mkdirs()
                verFile.write("package $projectGroup.$projectName\n\ninternal const val ${upperProjectName}_VERSION = \"$projectVersion\"")
            }
        }
    }

    static String executeShell(String cmd, File dir = null) {
        def out = new StringBuilder()
        def err = new StringBuilder()
        def acmd

        if (Os.isFamily(Os.FAMILY_WINDOWS)) {
            acmd = "cmd /c $cmd"
        } else {
            acmd = cmd
        }
        def res
        if (dir != null) {
            res = acmd.execute((String[])null, dir)
        } else {
            res = acmd.execute()
        }
        res.waitForProcessOutput(out, err)
        //println("out: $out")
        //println("err: $err")
        return out.toString()
    }


    static String capitalize(String str) { return str[0].toUpperCase() + str[1..-1] }
}
