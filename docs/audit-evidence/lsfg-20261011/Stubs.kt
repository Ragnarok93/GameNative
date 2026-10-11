package app.gamenative.powercontrol
object PowerManager {
 data class Profile(val adaptiveFpsCapEnabled:Boolean=true)
 data class Metrics(val timestampMs:Long=System.currentTimeMillis(), val fps:Float=30f, val totalFrameCount:Int=60)
 data class Observation(val sourceEquivalentFps:Float,val sourceTargetFps:Int)
 var currentProfile = Profile()
 var targetFps=60
 var latestMetrics:Metrics?=Metrics()
 var frameSampleStride=1
 var generation=0L
 var userEditGeneration=0L
 val applied=mutableListOf<Int>()
 fun reset() { targetFps=60;generation=0;userEditGeneration=0;applied.clear() }
 fun markUserFpsCapEdited() { userEditGeneration++;generation++ }
 fun currentFpsCapGeneration():Long=generation
 fun currentUserFpsCapEditGeneration():Long=userEditGeneration
 fun tuningObservation(fps:Float)=Observation(fps,targetFps)
 fun tunerTrimmedSteps():Int?=null
 fun tunerIsHarvesting():Boolean?=null
 fun applyFpsCapToEngines(fps:Int,expectedGeneration:Long?=null,expectedUserEditGeneration:Long?=null,expectedCurrentCapFps:Int?=null):Boolean {
  if(expectedGeneration!=null&&generation!=expectedGeneration)return false
  if(expectedUserEditGeneration!=null&&userEditGeneration!=expectedUserEditGeneration)return false
  if(expectedCurrentCapFps!=null&&targetFps!=expectedCurrentCapFps)return false
  generation++;applied+=fps;targetFps=fps;return true
 }
 fun openTunerClocksForProbe() {}
}
