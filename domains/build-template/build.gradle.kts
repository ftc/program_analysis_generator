// Builds one domain, named by -PdomainDir, against one api jar, -PapiJar:
//
//   domains/build-template/gradlew -p domains/build-template \
//       -PdomainDir=/abs/path/domains/interval -PapiJar=/abs/path/pag-api.jar build
//
// The domain directory holds only src/ (the domain) and test/ (its JUnit tests).
// Output goes to <domainDir>/build/, and the jar is named after the directory.
// The domain may use nothing but the JDK and pag.api (§5.4); no other dependency
// is on its compile classpath.

plugins {
    java
}

fun required(name: String): File =
    file(providers.gradleProperty(name).orNull ?: error("pass -P$name=<path>; see the comment at the top of build.gradle.kts"))

val domainDir: File = required("domainDir")
val apiJar: File = required("apiJar")

layout.buildDirectory.set(domainDir.resolve("build"))

sourceSets {
    main { java.setSrcDirs(listOf(domainDir.resolve("src"))) }
    test { java.setSrcDirs(listOf(domainDir.resolve("test"))) }
}

repositories {
    mavenCentral()
}

dependencies {
    // pag.api is provided by the engine at runtime, so it is not packaged.
    compileOnly(files(apiJar))
    testImplementation(files(apiJar))
    testImplementation(platform("org.junit:junit-bom:6.1.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(21)
}

tasks.jar {
    archiveBaseName.set(domainDir.name)
}

tasks.test {
    useJUnitPlatform()
    testLogging { events("passed", "failed") }
}
