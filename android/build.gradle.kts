plugins {
    id("com.android.application") version "8.5.2" apply false
    id("org.jetbrains.kotlin.android") version "1.9.24" apply false
}

// Build output goes outside OneDrive: syncing locks files in build/ and breaks the build.
allprojects {
    layout.buildDirectory.set(file("${System.getProperty("user.home")}/Android/build/Husky701.Android/${project.name}"))
}
