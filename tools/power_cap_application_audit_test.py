"""Compile actual PowerManager cap publication with deterministic Android fakes.

This local audit substitutes only Looper/Handler and external limiter engines.
Target/applier publication and the acknowledgement function are extracted intact.
"""
from pathlib import Path
import argparse
import os
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("--cache", type=Path, default=Path(os.environ.get(
    "KOTLIN_AUDIT_CACHE", str(ROOT.parent / "kotlin-audit-cache"))),
    help="Directory containing Kotlin 2.1.20 compiler and runtime dependencies")
CACHE = parser.parse_args().cache
if not (CACHE / "kotlin-stdlib-2.1.20.jar").is_file():
    parser.error("Kotlin compiler cache unavailable; provide --cache or KOTLIN_AUDIT_CACHE")
source = (ROOT / "app/src/main/java/app/gamenative/powercontrol/PowerManager.kt").read_text()

def function(signature):
    start = source.index(signature)
    opening = source.index("{", start)
    depth, end = 1, opening + 1
    while depth:
        depth += (source[end] == "{") - (source[end] == "}")
        end += 1
    return source[start:end]

target = source[source.index("    var targetFps: Int ="):source.index("    var currentFps: Float =")]
applier = source[source.index("    var fpsCapApplier:"):source.index("    fun invalidatePendingFpsCap()")]
power = '''package app.gamenative.powercontrol
import android.os.Handler
import android.os.Looper
import app.gamenative.PluviaApp
import com.winlator.xserver.extensions.PresentExtension
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicBoolean
object PowerManager {
private val fpsCapGeneration=AtomicLong(0L)
var frameGenerationPacing:Any=Any()
''' + '@Volatile\n' + target + '@Volatile\n' + applier + function("    fun invalidatePendingFpsCap()") + "\n" + function("    internal fun applyFpsCapToEngines(") + "\n}\n"

files = {
    "Power.kt": power,
    "Android.kt": '''package android.os
import java.util.concurrent.ConcurrentLinkedQueue
object Queue {
 val items=ConcurrentLinkedQueue<Runnable>();val mainThread=Thread.currentThread();var canPost=true
 fun idle() { while(true) (items.poll() ?: break).run() }
}
class Looper { companion object {
 val main=Looper();fun getMainLooper()=main
 fun myLooper():Looper?=if(Thread.currentThread()===Queue.mainThread) main else null
} }
class Handler(val looper:Looper) {
 fun post(task:Runnable):Boolean { if(!Queue.canPost)return false;Queue.items.add(task);return true }
 fun removeCallbacks(task:Runnable) { Queue.items.remove(task) }
}
''',
    "App.kt": '''package app.gamenative
import com.winlator.widget.View
object PluviaApp { @Volatile var xServerView:View?=null }
''',
    "View.kt": '''package com.winlator.widget
class Server { fun <T> getExtension(opcode:Int):T?=null }
class View {
 val limits=mutableListOf<Int>()
 fun getxServer():Server?=null
 fun setFrameRateLimit(value:Int) { limits.add(value) }
}
''',
    "Present.kt": '''package com.winlator.xserver.extensions
class PresentExtension { fun setFrameRateLimit(value:Int) {};companion object {const val MAJOR_OPCODE=1} }
''',
    "Shm.kt": '''package com.winlator.xserver
object ShmFramePacer { var applied=0;fun setFrameRateLimit(value:Int) {applied=value} }
''',
    "Main.kt": '''import app.gamenative.powercontrol.PowerManager as P
import app.gamenative.PluviaApp as A
import com.winlator.widget.View
import com.winlator.xserver.ShmFramePacer as S
import android.os.Queue
import java.util.concurrent.atomic.AtomicReference
fun main() {
 var failed=0
 fun check(name:String,condition:Boolean) {
  println("${if(condition) "PASS" else "FAIL"}: $name");if(!condition)failed++
 }
 fun setup():View {
  Queue.idle();Queue.canPost=true;P.targetFps=30;P.fpsCapApplier=null
  P.frameGenerationPacing=Any();S.applied=0;val v=View();A.xServerView=v;return v
 }
 fun post():Pair<Thread,AtomicReference<Boolean?>> {
  val result=AtomicReference<Boolean?>();val worker=Thread {result.set(P.applyFpsCapToEngines(60))}
  worker.start();val deadline=System.nanoTime()+1000000000L
  while(Queue.items.isEmpty() && worker.isAlive && System.nanoTime()<deadline)Thread.yield()
  return worker to result
 }
 fun finish(request:Pair<Thread,AtomicReference<Boolean?>>):Boolean? {
  request.first.join(5000);check("worker completes",!request.first.isAlive);return request.second.get()
 }
 var view=setup();var attempts=0;P.fpsCapApplier={attempts++;false}
 val rejected=P.applyFpsCapToEngines(60);Queue.idle()
 check("installed rejection authoritative",!rejected && attempts==1 && P.targetFps==30 && view.limits.isEmpty() && S.applied==0)
 view=setup();var request=post();val timeoutRejected=finish(request)==false;Queue.idle()
 check("timeout rejects late work",timeoutRejected && P.targetFps==30 && view.limits.isEmpty() && S.applied==0)
 view=setup();request=post();request.first.join(50)
 val pending=request.second.get()==null && P.targetFps==30 && view.limits.isEmpty()
 Queue.idle();val acknowledged=finish(request)
 check("main application acknowledged",pending && acknowledged==true && P.targetFps==60 && view.limits==listOf(60) && S.applied==60)
 view=setup();request=post();val replacement=View();A.xServerView=replacement;Queue.idle()
 check("owner replacement rejected",finish(request)==false && view.limits.isEmpty() && replacement.limits.isEmpty() && P.targetFps==30 && S.applied==0)
 view=setup();request=post();P.targetFps=90;Queue.idle()
 check("newer target edit invalidates",finish(request)==false && view.limits.isEmpty() && P.targetFps==90 && S.applied==0)
 view=setup();request=post();P.targetFps=90;P.targetFps=30;Queue.idle()
 check("ABA target edit invalidates",finish(request)==false && view.limits.isEmpty() && P.targetFps==30 && S.applied==0)
 view=setup();attempts=0;P.fpsCapApplier={attempts++;true};request=post();request.first.join(50)
 val handlerPending=request.second.get()==null && attempts==0
 Queue.idle();check("installed handler runs on main before acknowledgement",handlerPending && finish(request)==true && attempts==1 && view.limits.isEmpty() && S.applied==0)
 view=setup();attempts=0;P.fpsCapApplier={attempts++;true};request=post();P.fpsCapApplier={true};Queue.idle()
 check("applier replacement invalidates",finish(request)==false && attempts==0 && view.limits.isEmpty() && S.applied==0)
 view=setup();request=post();P.frameGenerationPacing=Any();Queue.idle()
 check("pacing identity change invalidates",finish(request)==false && view.limits.isEmpty() && P.targetFps==30 && S.applied==0)
 view=setup();request=post();request.first.interrupt();val interrupted=finish(request)==false;Queue.idle()
 check("interruption cancels late work",interrupted && view.limits.isEmpty() && P.targetFps==30 && S.applied==0)
 view=setup();Queue.canPost=false;request=post()
 check("failed Handler post rejects",finish(request)==false && view.limits.isEmpty() && P.targetFps==30 && S.applied==0)
 if(failed>0)kotlin.system.exitProcess(1)
}
''',
}

with tempfile.TemporaryDirectory(prefix="power-cap-extracted-") as directory:
    path = Path(directory)
    for name, text in files.items():
        (path / name).write_text(text)
    compiler_path = ":".join(str(jar) for jar in CACHE.glob("*.jar"))
    compilation = subprocess.run([
        "java", "-cp", compiler_path, "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler",
        "-no-stdlib", "-no-reflect", "-classpath", str(CACHE / "kotlin-stdlib-2.1.20.jar"),
        "-d", str(path / "classes"), *[str(path / name) for name in files],
    ], capture_output=True, text=True)
    if compilation.returncode:
        print(compilation.stderr)
        raise SystemExit(compilation.returncode)
    run = subprocess.run([
        "java", "-cp", str(path / "classes") + ":" + str(CACHE / "kotlin-stdlib-2.1.20.jar"), "MainKt",
    ])
    raise SystemExit(run.returncode)
