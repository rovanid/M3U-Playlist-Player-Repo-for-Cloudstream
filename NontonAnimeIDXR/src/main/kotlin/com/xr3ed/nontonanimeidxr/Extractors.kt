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
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.ExtractorLinkType

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

open class KotakanimeidLink : ExtractorApi() {
    override val name = "KotakAnimeID"
    override val mainUrl = "https://kotakanimeid.link"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val pageReferer = referer?.takeIf { it.isNotBlank() } ?: "https://s13.nontonanimeid.boats/"
        val res = app.get(
            url,
            referer = pageReferer,
            headers = mapOf(
                "User-Agent" to TurnstileInterceptor.USER_AGENT,
                "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"
            )
        )
        val html = res.text

        val m3u8Url = decryptKotakScript(html)
        if (!m3u8Url.isNullOrBlank()) {
            val tracks = runCatching {
                M3u8Helper.generateM3u8(
                    source = name,
                    streamUrl = m3u8Url,
                    referer = url,
                    headers = mapOf(
                        "Referer" to url,
                        "Origin" to (runCatching { java.net.URI(url).let { "${it.scheme}://${it.host}" } }.getOrNull() ?: "")
                    )
                )
            }.getOrNull()

            if (!tracks.isNullOrEmpty()) {
                tracks.forEach(callback)
                return
            }

            callback.invoke(
                newExtractorLink(
                    source = name,
                    name = "$name 1080p",
                    url = m3u8Url,
                    type = ExtractorLinkType.M3U8
                ) {
                    this.referer = url
                    this.quality = Qualities.P1080.value
                    this.headers = mapOf("Referer" to url)
                }
            )
        }
    }

    companion object {
        fun decryptKotakScript(html: String): String? {
            return runCatching {
                val sMatch = Regex("""var\s+s\s*=\s*['"]([0-9,]+)['"]\.split""").find(html) ?: return null
                val sList = sMatch.groupValues[1].split(",")
                val mulMatch = Regex("""parseInt\(s\[j\],(?:\s*10)?\)\s*-\s*(\d+)\s*\*\s*\(j\s*\+\s*(\d+)\)""").find(html) ?: return null
                val mul = mulMatch.groupValues[1].toInt()
                val add = mulMatch.groupValues[2].toInt()

                val key = ByteArray(sList.size)
                for (j in sList.indices) {
                    val v = sList[j].trim().toInt() - mul * (j + add)
                    key[j] = (v and 0xFF).toByte()
                }

                val b64Matches = Regex("""['"]([A-Za-z0-9+/=]{20,})['"]""").findAll(html)
                val fullB64 = b64Matches.map { it.groupValues[1] }.joinToString("")
                if (fullB64.isBlank()) return null

                val raw = android.util.Base64.decode(fullB64, android.util.Base64.DEFAULT)
                val dec = ByteArray(raw.size)
                for (i in raw.indices) {
                    dec[i] = (raw[i].toInt() xor key[i % key.size].toInt()).toByte()
                }
                val decText = String(dec, Charsets.UTF_8)
                val m3u8Match = Regex(""""file"\s*:\s*"(https?://[^"]+index\.m3u8)"""").find(decText)
                    ?: Regex("""https?://[^\s"'<>\\]+index\.m3u8""").find(decText)
                m3u8Match?.groupValues?.getOrNull(1) ?: m3u8Match?.value
            }.getOrNull()
        }
    }
}

class EmbedKotakAnimeid : KotakanimeidLink() {
    override val name = "EmbedKotakAnimeid"
    override val mainUrl = "https://embed2.kotakanimeid.com"
}

class Kotaksb : KotakanimeidLink() {
    override val name = "Kotaksb"
    override val mainUrl = "https://kotaksb.fun"
}

class KotakAnimeidCom : KotakanimeidLink() {
    override val name = "KotakAnimeid"
    override val mainUrl = "https://kotakanimeid.com"
}

class KotakAnimeidLinkS1 : KotakanimeidLink() {
    override val name = "KotakAnimeidLink"
    override val mainUrl = "https://s1.kotakanimeid.link"
}

class KotakAnimeidLinkS2 : KotakanimeidLink() {
    override val name = "KotakAnimeidLink"
    override val mainUrl = "https://s2.kotakanimeid.link"
}

class KotakAnimeidLinkS3 : KotakanimeidLink() {
    override val name = "KotakAnimeidLink"
    override val mainUrl = "https://s3.kotakanimeid.link"
}

class KotakAnimeidLinkRoot : KotakanimeidLink() {
    override val name = "KotakAnimeidLink"
    override val mainUrl = "https://kotakanimeid.link"
}

class KotakvideoCom : KotakanimeidLink() {
    override val name = "Kotakvideo"
    override val mainUrl = "https://kotakvideo.com"
}

class KotakvideoNet : KotakanimeidLink() {
    override val name = "Kotakvideo"
    override val mainUrl = "https://kotakvideo.net"
}

class StreamkuTv : KotakanimeidLink() {
    override val name = "Streamku"
    override val mainUrl = "https://streamku.tv"
}

class StreamkuCom : KotakanimeidLink() {
    override val name = "Streamku"
    override val mainUrl = "https://streamku.com"
}

class StreamkuNet : KotakanimeidLink() {
    override val name = "Streamku"
    override val mainUrl = "https://streamku.net"
}

class StreamkuLive : KotakanimeidLink() {
    override val name = "Streamku"
    override val mainUrl = "https://streamku.live"
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
