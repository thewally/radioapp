// AGP 9 heeft Kotlin ingebouwd en bundelt standaard KGP 2.2.10. Om de Kotlin-versie
// uit de dependency-update te behouden, wordt KGP hier expliciet op de buildscript-classpath gezet.
buildscript {
    repositories {
        google()
        mavenCentral()
    }
    dependencies {
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.21")
    }
}

plugins {
    id("com.android.application") version "9.5.0" apply false
}
