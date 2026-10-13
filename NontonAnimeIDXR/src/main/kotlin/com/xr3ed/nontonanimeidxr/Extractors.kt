package com.xr3ed.nontonanimeidxr

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.APIHolder
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.extractors.Filesim
import com.lagradost.cloudstream3.extractors.Hxfile
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.INFER_TYPE
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.extractors.VidStack
import com.lagradost.cloudstream3.utils.newExtractorLink

open class Gdplayer : ExtractorApi() {
    override val name = "Gdplayer"
    override val mainUrl = "https://gdplayer.to"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val res = app.get(url, referer = referer).document
        val script = res.selectFirst("script:containsData(player = \"\")")?.data()
        val kaken = script?.substringAfter("kaken = \"")?.substringBefore("\"")

        val json = app.get(
            "$mainUrl/api/?${kaken ?: return}=&_=${APIHolder.unixTimeMS}",
            headers = mapOf(
                "X-Requested-With" to "XMLHttpRequest"
            )
        ).parsedSafe<Response>()

        json?.sources?.map {
            callback.invoke(
                newExtractorLink(
                    this.name,
                    this.name,
                    it.file ?: return@map,
                    INFER_TYPE
                ) {
                    this.quality = getQuality(json.title)
                }
            )
        }
    }

    private fun getQuality(str: String?): Int {
        return Regex("(\\d{3,4})[pP]").find(str ?: "")?.groupValues?.getOrNull(1)?.toIntOrNull()
            ?: Qualities.Unknown.value
    }

    data class Response(
        @JsonProperty("title") val title: String? = null,
        @JsonProperty("sources") val sources: ArrayList<Sources>? = null,
    ) {
        data class Sources(
            @JsonProperty("file") val file: String? = null,
            @JsonProperty("type") val type: String? = null,
        )
    }

}

class Nontonanimeid : Hxfile() {
    override val name = "Nontonanimeid"
    override val mainUrl = "https://nontonanimeid.com"
    override val requiresReferer = true
}

class EmbedKotakAnimeid : Hxfile() {
    override val name = "EmbedKotakAnimeid"
    override val mainUrl = "https://embed2.kotakanimeid.com"
    override val requiresReferer = true
}

class Kotaksb : Hxfile() {
    override val name = "Kotaksb"
    override val mainUrl = "https://kotaksb.fun"
    override val requiresReferer = true
}

class KotakAnimeidCom : Hxfile() {
    override val name = "KotakAnimeid"
    override val mainUrl = "https://kotakanimeid.com"
    override val requiresReferer = true
}

class KotakAnimeidLinkS1 : Hxfile() {
    override val name = "KotakAnimeidLink"
    override val mainUrl = "https://s1.kotakanimeid.link"
    override val requiresReferer = true
}

class KotakAnimeidLinkS2 : Hxfile() {
    override val name = "KotakAnimeidLink"
    override val mainUrl = "https://s2.kotakanimeid.link"
    override val requiresReferer = true
}

class KotakAnimeidLinkS3 : Hxfile() {
    override val name = "KotakAnimeidLink"
    override val mainUrl = "https://s3.kotakanimeid.link"
    override val requiresReferer = true
}

class KotakAnimeidLinkRoot : Hxfile() {
    override val name = "KotakAnimeidLink"
    override val mainUrl = "https://kotakanimeid.link"
    override val requiresReferer = true
}

class KotakvideoCom : Hxfile() {
    override val name = "Kotakvideo"
    override val mainUrl = "https://kotakvideo.com"
    override val requiresReferer = true
}

class KotakvideoNet : Hxfile() {
    override val name = "Kotakvideo"
    override val mainUrl = "https://kotakvideo.net"
    override val requiresReferer = true
}

class StreamkuTv : Hxfile() {
    override val name = "Streamku"
    override val mainUrl = "https://streamku.tv"
    override val requiresReferer = true
}

class StreamkuCom : Hxfile() {
    override val name = "Streamku"
    override val mainUrl = "https://streamku.com"
    override val requiresReferer = true
}

class StreamkuNet : Hxfile() {
    override val name = "Streamku"
    override val mainUrl = "https://streamku.net"
    override val requiresReferer = true
}

class StreamkuLive : Hxfile() {
    override val name = "Streamku"
    override val mainUrl = "https://streamku.live"
    override val requiresReferer = true
}

class NontonkuCom : Hxfile() {
    override val name = "Nontonku"
    override val mainUrl = "https://nontonku.com"
    override val requiresReferer = true
}

class NontonkuOrg : Hxfile() {
    override val name = "Nontonku"
    override val mainUrl = "https://nontonku.org"
    override val requiresReferer = true
}

class Vidhidepre : Filesim() {
    override val name = "Vidhidepre"
    override var mainUrl = "https://vidhidepre.com"
}

class Rpmvip : VidStack() {
    override var name = "Rpmvip"
    override var mainUrl = "https://s1.rpmvip.com"
    override var requiresReferer = true
}

class RpmvipS2 : VidStack() {
    override var name = "Rpmvip"
    override var mainUrl = "https://s2.rpmvip.com"
    override var requiresReferer = true
}

class RpmvipS3 : VidStack() {
    override var name = "Rpmvip"
    override var mainUrl = "https://s3.rpmvip.com"
    override var requiresReferer = true
}

class RpmvipVip : VidStack() {
    override var name = "Rpmvip"
    override var mainUrl = "https://vip.rpmvip.com"
    override var requiresReferer = true
}

class RpmvipRoot : VidStack() {
    override var name = "Rpmvip"
    override var mainUrl = "https://rpmvip.com"
    override var requiresReferer = true
}
