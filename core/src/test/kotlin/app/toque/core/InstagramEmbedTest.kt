package app.toque.core

import app.toque.core.foyer.FoyerFeed
import app.toque.core.recipes.InstagramEmbed
import app.toque.core.recipes.Recipe
import app.toque.core.recipes.RecipeCodec
import app.toque.core.recipes.RecipeSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class InstagramEmbedTest {
    @Test fun findsThePostCode() {
        assertEquals("DAbC_12-xyz", InstagramEmbed.shortcode("https://www.instagram.com/reel/DAbC_12-xyz/?igsh=MWx0"))
        assertEquals("Cq9zz8dLs4P", InstagramEmbed.shortcode("https://instagram.com/p/Cq9zz8dLs4P/"))
        assertEquals("DAbC_12-xyz", InstagramEmbed.shortcode("https://www.instagram.com/cheffe.dupont/reel/DAbC_12-xyz/"))
        assertEquals("DAbC_12-xyz", InstagramEmbed.shortcode("https://www.instagram.com/reels/DAbC_12-xyz"))
        assertEquals("https://www.instagram.com/p/DAbC_12-xyz/embed/captioned/", InstagramEmbed.embedUrl("https://www.instagram.com/tv/DAbC_12-xyz/"))
        assertNull(InstagramEmbed.shortcode("https://www.instagram.com/cheffe.dupont/"))
        assertNull(InstagramEmbed.shortcode("http://www.instagram.com/reel/DAbC_12-xyz/"))
        assertNull(InstagramEmbed.shortcode("https://evil-instagram.com/reel/DAbC_12-xyz/"))
        assertNull(InstagramEmbed.shortcode("https://www.tiktok.com/@a/video/123"))
    }

    @Test fun readsVideoAndPosterFromTheScript() {
        val html = """<script>window.__additionalDataLoaded('extra',{"shortcode_media":{"is_video":true,""" +
            """"display_url":"https:\/\/scontent.cdninstagram.com\/v\/t51\/p.jpg?stp=s640x640&oe=673F",""" +
            """"video_url":"https:\/\/scontent.cdninstagram.com\/o1\/v\/t16\/f2\/m86\/v.mp4?efg=x&oh=00_AY&oe=673F"}});</script>"""
        val m = InstagramEmbed.parse(html)
        assertEquals("https://scontent.cdninstagram.com/o1/v/t16/f2/m86/v.mp4?efg=x&oh=00_AY&oe=673F", m.video)
        assertEquals("https://scontent.cdninstagram.com/v/t51/p.jpg?stp=s640x640&oe=673F", m.poster)
    }

    @Test fun posterFromTheEmbeddedImageWhenThereIsNoVideo() {
        val html = """<a class="EmbeddedMedia"><img class="EmbeddedMediaImage" alt="x"
            src="https://scontent.cdninstagram.com/1.jpg?a=1&amp;b=2"
            srcset="https://scontent.cdninstagram.com/1.jpg?s=640&amp;b=2 640w,https://scontent.cdninstagram.com/1.jpg?s=1080&amp;b=2 1080w" /></a>"""
        val m = InstagramEmbed.parse(html)
        assertNull(m.video)
        assertEquals("https://scontent.cdninstagram.com/1.jpg?s=1080&b=2", m.poster)
    }

    @Test fun nothingFromALoginPageOrUnsafeAddresses() {
        assertEquals(InstagramEmbed.Media(null, null), InstagramEmbed.parse("<html><title>Login • Instagram</title></html>"))
        val unsafe = """{"video_url":"http:\/\/insecure.example\/v.mp4","display_url":"javascript:alert(1)"}"""
        assertEquals(InstagramEmbed.Media(null, null), InstagramEmbed.parse(unsafe))
    }

    @Test fun linkAndVideoSurviveTheCodecAndTheFeed() {
        val r = Recipe(id = "foyer-1", title = "Capellini", source = RecipeSource.FOYER,
            link = "https://www.instagram.com/reel/DAbC/", video = "https://www.instagram.com/reel/DAbC/")
        assertEquals(r, RecipeCodec.decode(RecipeCodec.encode(r), RecipeSource.IMPORTED))
        // A recipe without them keeps a compact file.
        val plain = Recipe(id = "mc-1", title = "Aligot")
        assertEquals(false, RecipeCodec.encode(plain).contains("\"video\""))

        val feed = """{"format":"toque-recettes-1","recipes":[{"id":"foyer-0b6f7c3e-1d2a-4c5b-9e8f-7a6b5c4d3e2f","title":"Capellini",""" +
            """"steps":[{"text":"Cuire."}],"link":"https://www.instagram.com/reel/DAbC/","video":"https://www.instagram.com/reel/DAbC/"},""" +
            """{"id":"foyer-1b6f7c3e-1d2a-4c5b-9e8f-7a6b5c4d3e2f","title":"Piège","steps":[{"text":"x"}],"link":"javascript:alert(1)","video":"file:///sdcard/x.mp4"}]}"""
        val f = FoyerFeed.parse(feed)
        assertEquals("https://www.instagram.com/reel/DAbC/", f.recipes[0].video)
        assertEquals("https://www.instagram.com/reel/DAbC/", f.recipes[0].link)
        assertEquals("", f.recipes[1].link)
        assertEquals("", f.recipes[1].video)
    }
}
