package io.github.androiduirenderer.mcp

import kotlin.test.Test
import kotlin.test.assertFailsWith

class RenderRequestValidatorTest {
    @Test
    fun `accepts explicit fixture state`() {
        RenderRequestValidator.validate(
            RenderRequest(
                layout = "item_cart_product",
                fixture = mapOf(
                    "@id/productName" to ViewFixture(text = "Extractor"),
                    "discountBadge" to ViewFixture(text = "-70%", visibility = "visible", backgroundColor = "#FFCC00"),
                    "productImage" to ViewFixture(image = FixtureImage(FixtureImageType.COLOR, "#E8E8E8")),
                ),
            ),
        )
    }

    @Test
    fun `rejects fixture values that cannot be safely applied`() {
        assertFailsWith<RendererException> {
            RenderRequestValidator.validate(
                RenderRequest(layout = "screen", fixture = mapOf("@id/title" to ViewFixture(visibility = "expanded"))),
            )
        }
        assertFailsWith<RendererException> {
            RenderRequestValidator.validate(
                RenderRequest(layout = "screen", fixture = mapOf("@id/image" to ViewFixture(image = FixtureImage(FixtureImageType.COLOR, "red")))),
            )
        }
    }

    @Test
    fun `accepts exact device pixels but forbids ambiguous width units`() {
        RenderRequestValidator.validate(RenderRequest(layout = "screen", widthPx = 722, densityDpi = 212))
        assertFailsWith<RendererException> {
            RenderRequestValidator.validate(RenderRequest(layout = "screen", widthDp = 545, widthPx = 722, densityDpi = 212))
        }
    }
}
