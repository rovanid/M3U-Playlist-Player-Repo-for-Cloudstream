// use an integer for version numbers
version = 11

android {
    namespace = "com.xr3ed.nontonanimeidxr"
    buildFeatures {
        buildConfig = true
    }
    sourceSets {
        getByName("main").java.srcDirs("src/main/kotlin", "${project.rootDir}/shared/src/main/kotlin")
    }
}

cloudstream {
    language = "id"
    description = "NontonAnimeID — Streaming Anime Subtitle Indonesia (#Anime NontonAnimeIDXR)"
    authors = listOf("xr3ed")
    status = 1
    tvTypes = listOf(
        "AnimeMovie",
        "Anime",
        "OVA"
    )

    iconUrl = "https://t2.gstatic.com/faviconV2?client=SOCIAL&type=FAVICON&fallback_opts=TYPE,SIZE,URL&url=https://s13.nontonanimeid.boats&size=%size%"
}
