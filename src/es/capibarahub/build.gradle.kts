import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

keiyoushi {
    name = "Capibara Traductor"
    versionCode = 10
    contentWarning = ContentWarning.NSFW
    libVersion = "1.6"
    source {
        lang = "es"
        baseUrl = "https://capibaratraductor.com"
    }
}
