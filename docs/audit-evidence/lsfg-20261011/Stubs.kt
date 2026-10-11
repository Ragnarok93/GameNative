package app.gamenative.powercontrol
object PowerManager {
 data class Profile(val adaptiveFpsCapEnabled:Boolean=true)
 data class Metrics(val timestampMs:Long=System.currentTimeMillis(), val fps:Float=30f, val totalFrameCount:Int=60)
 data class Observation(val sourceEquivalentFps:Float,val sourceTargetFps:Int)
 var currentProfile = Profile(); var targetFps=60; var latestMetrics:Metrics?=Metrics(); var frameSampleStride=1
 val applied=mutableListOf<Int>()
 fun tuningObservation(fps:Float)=Observation(fps,targetFps)
 fun tunerTrimmedSteps():Int?=null
 fun tunerIsHarvesting():Boolean?=null
 fun applyFpsCapToEngines(fps:Int):Boolean { applied+=fps; targetFps=fps; return true }
 fun openTunerClocksForProbe() {}
}
