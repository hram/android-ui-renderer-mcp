package io.github.androiduirenderer.mcp

import java.io.BufferedWriter
import java.io.OutputStreamWriter
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.io.path.exists
import kotlin.system.measureTimeMillis
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

interface RendererWorker : AutoCloseable { fun render(request: RenderRequest): WorkerRenderResult }

class WorkerManager(private val config: RendererConfig, private val json: Json) : AutoCloseable {
    private var generation: Long? = null
    private var worker: RendererWorker? = null

    fun ensure(generation: Long): Boolean {
        if (this.generation == generation && worker != null) return false
        close()
        worker = if (config.workerCommand == null) SidecarWorker(config, json) else ProcessWorker(config, json).also { it.start(generation) }
        this.generation = generation
        return true
    }
    fun render(request: RenderRequest) = worker?.render(request) ?: throw RendererException("WORKER_NOT_STARTED", "Renderer worker has not been started")
    override fun close() { worker?.close(); worker = null; generation = null }
}

/** Temporary source + init script: no file in the consumer Android repository is changed. */
private class SidecarWorker(private val config: RendererConfig, private val json: Json) : RendererWorker {
    override fun render(request: RenderRequest): WorkerRenderResult {
        val run = config.outputDir.resolve("sidecar").resolve(UUID.randomUUID().toString())
        val sourceRoot = run.resolve("src")
        val output = run.resolve("render.png")
        val tree = run.resolve("view-tree.json")
        val timings = run.resolve("timings.json")
        Files.createDirectories(sourceRoot)
        val packageName = androidPackage()
        val source = sourceRoot.resolve(packageName.replace('.', '/')).resolve("AndroidUiRendererProbe.java")
        Files.createDirectories(source.parent)
        Files.writeString(source, probeSource(packageName, request))
        val init = run.resolve("init.gradle")
        Files.writeString(init, initScript())
        val gradlew = config.projectRoot.resolve(if (System.getProperty("os.name").startsWith("Windows")) "gradlew.bat" else "gradlew")
        if (!gradlew.exists()) throw RendererException("PROJECT_NOT_FOUND", "Gradle wrapper not found at $gradlew")
        val command = listOf(gradlew.toAbsolutePath().toString(), config.allowedTasks.single(), "--tests", "$packageName.renderer.AndroidUiRendererProbe", "--init-script", init.toString(), "--console=plain")
        var log = ""
        var exitCode = -1
        val gradleMs = measureTimeMillis {
            val process = ProcessBuilder(command).directory(config.projectRoot.toFile()).redirectErrorStream(true)
                .apply {
                    environment()["AUR_PROBE_SOURCE"] = sourceRoot.toString()
                    environment()["AUR_OUTPUT"] = output.toString()
                    environment()["AUR_TREE"] = tree.toString()
                    environment()["AUR_TIMINGS"] = timings.toString()
                }.start()
            log = process.inputStream.bufferedReader().readText()
            exitCode = process.waitFor()
        }
        if (exitCode != 0 || !output.exists() || !tree.exists() || !timings.exists()) {
            throw RendererException("RENDER_FAILED", "Temporary Robolectric render failed", kotlinx.serialization.json.JsonPrimitive(log.takeLast(16_000)))
        }
        val image = javax.imageio.ImageIO.read(output.toFile())
        val viewTree = try {
            json.decodeFromString(ViewNode.serializer(), Files.readString(tree))
        } catch (error: Exception) {
            throw RendererException("RENDER_FAILED", "Temporary Robolectric renderer produced an invalid View tree", kotlinx.serialization.json.JsonPrimitive(error.message ?: "unknown error"))
        }
        val probeRenderMs = try {
            json.parseToJsonElement(Files.readString(timings)).jsonObject["renderMs"]?.jsonPrimitive?.content?.toLongOrNull()
                ?: throw IllegalArgumentException("renderMs is missing")
        } catch (error: Exception) {
            throw RendererException("RENDER_FAILED", "Temporary Robolectric renderer produced invalid timings", kotlinx.serialization.json.JsonPrimitive(error.message ?: "unknown error"))
        }
        return WorkerRenderResult(image.width, image.height, output.toString(), viewTree, timings = WorkerTimings(gradleMs, probeRenderMs))
    }
    private fun androidPackage(): String {
        val moduleDir = config.projectRoot.resolve(config.module.removePrefix(":"))
        val manifest = moduleDir.resolve("src/main/AndroidManifest.xml")
        val text = if (manifest.exists()) Files.readString(manifest) else ""
        return Regex("package\\s*=\\s*\\\"([^\\\"]+)\\\"").find(text)?.groupValues?.get(1)
            ?: listOf(moduleDir.resolve("build.gradle.kts"), moduleDir.resolve("build.gradle"))
                .firstOrNull { it.exists() }
                ?.let { Regex("namespace\\s*=\\s*\\\"([^\\\"]+)\\\"").find(Files.readString(it))?.groupValues?.get(1) }
            ?: throw RendererException("PROJECT_CONFIG", "Cannot determine Android package from $manifest or module build file")
    }
    private fun initScript() = """
        def src = System.getenv('AUR_PROBE_SOURCE')
        gradle.allprojects { repositories { google(); mavenCentral() } }
        gradle.beforeProject { p -> if (p.path == '${config.module}') p.plugins.withId('com.android.application') {
          p.android.testOptions.unitTests.includeAndroidResources = true
          p.android.sourceSets.getByName('test').java.srcDir(src)
          p.dependencies.add('testImplementation', 'org.robolectric:robolectric:4.14.1')
          p.configurations.configureEach { resolutionStrategy.force('org.bouncycastle:bcprov-jdk18on:1.77') }
          p.tasks.withType(Test).configureEach { outputs.upToDateWhen { false } }
        }}
    """.trimIndent()
    private fun probeSource(pkg: String, r: RenderRequest): String {
        val density = r.densityDpi ?: 160
        val width = r.widthPx ?: ((r.widthDp ?: 1080) * density / 160f).toInt()
        val height = r.heightPx ?: r.heightDp?.let { (it * density / 160f).toInt() }
        val sets = r.fixture.entries.joinToString("\n") { (key, f) -> fixtureJava(key.removePrefix("@id/").removePrefix("@+id/"), f, pkg) }
        val heightSpec = height?.let { "View.MeasureSpec.makeMeasureSpec($it, View.MeasureSpec.EXACTLY)" } ?: "View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)"
        return """package $pkg.renderer;
import android.graphics.*; import android.graphics.drawable.*; import android.content.res.*; import android.util.DisplayMetrics; import android.view.*; import android.widget.*; import android.widget.CompoundButton;
import org.junit.*; import org.junit.runner.*; import org.robolectric.*; import org.robolectric.annotation.*; import java.io.*;
@RunWith(RobolectricTestRunner.class) @Config(sdk=34) @GraphicsMode(GraphicsMode.Mode.NATIVE) public class AndroidUiRendererProbe {
 @Test public void render() throws Exception { long renderStart=System.nanoTime(); Resources rs=RuntimeEnvironment.getApplication().getResources(); DisplayMetrics dm=new DisplayMetrics(); dm.setTo(rs.getDisplayMetrics()); dm.densityDpi=$density; dm.density=$density/160f; dm.scaledDensity=dm.density; Configuration c=new Configuration(rs.getConfiguration()); c.densityDpi=$density; rs.updateConfiguration(c,dm);
 View root=LayoutInflater.from(RuntimeEnvironment.getApplication()).inflate($pkg.R.layout.${r.layout},null,false); $sets
 root.measure(View.MeasureSpec.makeMeasureSpec($width,View.MeasureSpec.EXACTLY),$heightSpec); root.layout(0,0,$width,root.getMeasuredHeight()); writeTree(root,new File(System.getenv("AUR_TREE"))); Bitmap b=Bitmap.createBitmap($width,root.getMeasuredHeight(),Bitmap.Config.ARGB_8888); Canvas canvas=new Canvas(b); canvas.drawColor(Color.parseColor("${r.background ?: "#FFFFFF"}")); root.draw(canvas); File out=new File(System.getenv("AUR_OUTPUT")); out.getParentFile().mkdirs(); try(FileOutputStream s=new FileOutputStream(out)){b.compress(Bitmap.CompressFormat.PNG,100,s);} writeTimings(new File(System.getenv("AUR_TIMINGS")),(System.nanoTime()-renderStart)/1000000L); }
 static View v(View r,String n)throws Exception{return r.findViewById($pkg.R.id.class.getField(n).getInt(null));} static void vis(View v,String x){v.setVisibility(x.equals("gone")?8:x.equals("invisible")?4:0);}
 static void writeTimings(File out,long renderMs)throws Exception{out.getParentFile().mkdirs();try(FileOutputStream s=new FileOutputStream(out)){s.write(("{\"renderMs\":"+renderMs+"}").getBytes("UTF-8"));}}
 static void writeTree(View root,File out)throws Exception{out.getParentFile().mkdirs();try(FileOutputStream s=new FileOutputStream(out)){s.write(node(root,0,0).getBytes("UTF-8"));}}
 static String node(View v,int ox,int oy){int l=ox+v.getLeft(),t=oy+v.getTop();StringBuilder x=new StringBuilder("{"); field(x,"id",id(v)); x.append(','); field(x,"resourceName",resource(v)); x.append(",\"className\":"); str(x,v.getClass().getName()); x.append(",\"visibility\":"); str(x,visibility(v)); x.append(",\"enabled\":").append(v.isEnabled()); x.append(",\"clickable\":").append(v.isClickable()); x.append(",\"selected\":").append(v.isSelected()); if(v instanceof CompoundButton)x.append(",\"checked\":").append(((CompoundButton)v).isChecked()); if(v instanceof TextView){x.append(",\"text\":"); str(x,((TextView)v).getText()==null?null:((TextView)v).getText().toString());} x.append(",\"contentDescription\":"); str(x,v.getContentDescription()==null?null:v.getContentDescription().toString()); x.append(",\"bounds\":"); box(x,l,t,l+v.getWidth(),t+v.getHeight()); x.append(",\"measuredWidth\":").append(v.getMeasuredWidth()); x.append(",\"measuredHeight\":").append(v.getMeasuredHeight()); x.append(",\"margins\":"); margins(x,v); x.append(",\"padding\":"); box(x,v.getPaddingLeft(),v.getPaddingTop(),v.getPaddingRight(),v.getPaddingBottom()); x.append(",\"children\":["); if(v instanceof ViewGroup){ViewGroup g=(ViewGroup)v;for(int i=0;i<g.getChildCount();i++){if(i>0)x.append(',');x.append(node(g.getChildAt(i),l,t));}} return x.append("]}").toString();}
 static String id(View v){if(v.getId()==View.NO_ID)return null;try{return v.getResources().getResourceEntryName(v.getId());}catch(Exception e){return null;}} static String resource(View v){if(v.getId()==View.NO_ID)return null;try{return v.getResources().getResourceName(v.getId());}catch(Exception e){return null;}} static String visibility(View v){return v.getVisibility()==View.VISIBLE?"VISIBLE":v.getVisibility()==View.INVISIBLE?"INVISIBLE":"GONE";} static void field(StringBuilder x,String n,String v){x.append('\"').append(n).append("\":");str(x,v);} static void box(StringBuilder x,int l,int t,int r,int b){x.append("{\"left\":").append(l).append(",\"top\":").append(t).append(",\"right\":").append(r).append(",\"bottom\":").append(b).append('}');} static void margins(StringBuilder x,View v){ViewGroup.LayoutParams p=v.getLayoutParams();if(!(p instanceof ViewGroup.MarginLayoutParams)){x.append("null");return;}ViewGroup.MarginLayoutParams m=(ViewGroup.MarginLayoutParams)p;box(x,m.leftMargin,m.topMargin,m.rightMargin,m.bottomMargin);} static void str(StringBuilder x,String s){if(s==null){x.append("null");return;}x.append('\"');for(int i=0;i<s.length();i++){char c=s.charAt(i);if(c=='\\'||c=='\"')x.append('\\');if(c=='\n')x.append("\\n");else if(c=='\r')x.append("\\r");else if(c=='\t')x.append("\\t");else if(c<32)x.append(String.format("\\u%04x",(int)c));else x.append(c);}x.append('\"');}
} """
    }
    private fun fixtureJava(id: String, f: ViewFixture, pkg: String): String {
        fun q(s: String) = s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")
        val x = StringBuilder("try{View x=v(root,\"${q(id)}\");")
        f.text?.let { x.append("if(x instanceof TextView)((TextView)x).setText(\"${q(it)}\");") }; f.hint?.let { x.append("if(x instanceof TextView)((TextView)x).setHint(\"${q(it)}\");") }
        f.contentDescription?.let { x.append("x.setContentDescription(\"${q(it)}\");") }; f.visibility?.let { x.append("vis(x,\"$it\");") }; f.enabled?.let { x.append("x.setEnabled($it);") }; f.selected?.let { x.append("x.setSelected($it);") }; f.checked?.let { x.append("if(x instanceof CompoundButton)((CompoundButton)x).setChecked($it);") }
        f.backgroundColor?.let { x.append("x.setBackgroundColor(Color.parseColor(\"$it\"));") }; f.textColor?.let { x.append("if(x instanceof TextView)((TextView)x).setTextColor(Color.parseColor(\"$it\"));") }; f.textSizeSp?.let { x.append("if(x instanceof TextView)((TextView)x).setTextSize(${it}f);") }; f.strikeThrough?.let { if(it) x.append("if(x instanceof TextView)((TextView)x).setPaintFlags(((TextView)x).getPaintFlags()|16);") }
        f.image?.let {
            when (it.type) {
                FixtureImageType.COLOR -> x.append("if(x instanceof ImageView)((ImageView)x).setImageDrawable(new ColorDrawable(Color.parseColor(\"${it.value}\"))); ")
                FixtureImageType.LOCAL_PATH -> x.append("if(x instanceof ImageView){Bitmap b=BitmapFactory.decodeFile(\"${q(it.value)}\");if(b==null)throw new IllegalArgumentException(\"Cannot decode local fixture image\");((ImageView)x).setImageBitmap(b);}")
                FixtureImageType.DRAWABLE_RESOURCE -> {
                    val name = it.value.removePrefix("@drawable/")
                    x.append("if(x instanceof ImageView)((ImageView)x).setImageResource($pkg.R.drawable.class.getField(\"${q(name)}\").getInt(null));")
                }
            }
        }
        return x.append("}catch(Exception e){throw new RuntimeException(\"fixture @id/$id\",e);}").toString()
    }
    override fun close() {}
}

@Serializable private data class WorkerEnvelope(val type: String, val generation: Long? = null, val request: RenderRequest? = null)
private class ProcessWorker(private val config: RendererConfig, private val json: Json) : RendererWorker {
 private lateinit var p: Process; private lateinit var w: BufferedWriter; private lateinit var r: java.io.BufferedReader
 fun start(g:Long){ p=ProcessBuilder(listOfNotNull(config.workerCommand)+config.workerArgs).directory(config.projectRoot.toFile()).redirectError(ProcessBuilder.Redirect.INHERIT).start(); w=BufferedWriter(OutputStreamWriter(p.outputStream)); r=p.inputStream.bufferedReader(); send(WorkerEnvelope("initialize",g)); if(read()["type"]?.jsonPrimitive?.content!="ready") throw RendererException("WORKER_START_FAILED","Worker did not acknowledge initialize") }
 override fun render(q:RenderRequest):WorkerRenderResult { send(WorkerEnvelope("render",request=q)); val x=read(); if(x["type"]?.jsonPrimitive?.content!="rendered") throw RendererException("RENDER_FAILED","Worker did not render"); return json.decodeFromJsonElement(WorkerRenderResult.serializer(),x["result"]!!) }
 private fun send(x:WorkerEnvelope){w.write(json.encodeToString(WorkerEnvelope.serializer(),x));w.newLine();w.flush()} private fun read():JsonObject= json.parseToJsonElement(r.readLine()?:throw RendererException("WORKER_EXITED","Worker exited")).jsonObject
 override fun close(){if(::p.isInitialized&&p.isAlive){p.destroy();if(!p.waitFor(2,TimeUnit.SECONDS))p.destroyForcibly()}}
}
