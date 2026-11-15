plugins { `java-library` }
java { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
dependencies {
    api("com.nimbusds:nimbus-jose-jwt:10.10")
    implementation("org.mozilla:rhino:1.7.15.1")
    api("com.google.code.gson:gson:2.11.0")
    testImplementation("junit:junit:4.13.2")
}
tasks.test { workingDir(rootProject.projectDir) }
