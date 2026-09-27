package io.github.androiduirenderer.mcp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json

class ViewNodeTest {
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false; encodeDefaults = true }

    @Test
    fun `probe text layout is decoded including ellipsis and gone views`() {
        val tree = json.decodeFromString(ViewNode.serializer(), """
            {"id":"root","className":"android.widget.LinearLayout","bounds":{"left":0,"top":0,"right":722,"bottom":220},"children":[
              {"id":"productName","className":"android.widget.TextView","text":"Long name","textLayout":{"textSizePx":31.0,"maxLines":1,"lineCount":1,"ellipsisCount":27,"truncated":true},"bounds":{"left":182,"top":11,"right":701,"bottom":53},"children":[]},
              {"id":"mandatory","className":"android.widget.TextView","visibility":"GONE","textLayout":{"textSizePx":31.0,"maxLines":1,"lineCount":null,"ellipsisCount":null,"truncated":null},"bounds":{"left":182,"top":11,"right":182,"bottom":11},"children":[]}
            ]}
        """.trimIndent())

        val name = tree.children[0].textLayout!!
        assertEquals(27, name.ellipsisCount)
        assertTrue(name.truncated!!)
        val gone = tree.children[1].textLayout!!
        assertEquals(1, gone.maxLines)
        assertNull(gone.lineCount)
        assertNull(tree.textLayout)
    }
}
