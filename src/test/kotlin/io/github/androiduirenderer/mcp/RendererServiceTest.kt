package io.github.androiduirenderer.mcp

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json

class RendererServiceTest {
    @Test
    fun `first render builds and starts worker while unchanged render reuses it`() {
        val root = Files.createTempDirectory("renderer-service")
        val gradlew = root.resolve("gradlew")
        Files.writeString(gradlew, "#!/bin/sh\nexit 0\n")
        gradlew.toFile().setExecutable(true)
        val worker = root.resolve("fake-worker")
        Files.writeString(worker, """#!/bin/sh
read ignored
echo '{"type":"ready"}'
read ignored
echo '{"type":"rendered","result":{"widthPx":100,"heightPx":50,"screenshotPath":"/tmp/screen.png","viewTree":{"id":"root","className":"android.view.View","bounds":{"left":0,"top":0,"right":100,"bottom":50},"children":[{"id":"button","className":"android.widget.Button","text":"OK","bounds":{"left":10,"top":10,"right":90,"bottom":40}}]},"timings":{"gradleMs":12,"probeRenderMs":7}}}'
read ignored
echo '{"type":"rendered","result":{"widthPx":100,"heightPx":50,"screenshotPath":"/tmp/screen.png","viewTree":{"id":"root","className":"android.view.View","bounds":{"left":0,"top":0,"right":100,"bottom":50}}}}'
""")
        worker.toFile().setExecutable(true)
        Files.writeString(root.resolve(".android-ui-renderer.yaml"), """android:
  module: app
build:
  allowedTasks:
    - :app:assembleDebug
worker:
  command: $worker
renderer:
  outputDir: .android-ui-renderer
""")
        val json = Json { ignoreUnknownKeys = true }
        RendererService(ConfigLoader.load(root), json).use { service ->
            val (first, firstFreshness) = service.render(RenderRequest(layout = "screen_main"))
            assertTrue(firstFreshness.build.performed)
            assertTrue(firstFreshness.workerRestarted)
            assertTrue(firstFreshness.timings.gradleMs >= 12)
            assertEquals(7, firstFreshness.timings.renderMs)
            assertTrue(firstFreshness.timings.totalMs >= firstFreshness.timings.renderMs)
            assertEquals("button", service.inspect(first.id, "@id/button").id)

            val (_, secondFreshness) = service.render(RenderRequest(layout = "screen_main"))
            assertFalse(secondFreshness.build.performed)
            assertFalse(secondFreshness.workerRestarted)
        }
    }
}
