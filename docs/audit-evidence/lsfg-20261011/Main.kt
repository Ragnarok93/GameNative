package app.gamenative.powercontrol
import app.gamenative.powercontrol.autotuning.AdaptiveFpsCap
fun main() {
 for(userEdit in listOf(0,90)) {
  val state=AdaptiveFpsCap(); state.observeCap(60)
  repeat(30) { state.onCycle(30f,true,true)?.let(state::commit) }
  check(state.effectiveCapFps==45)
  val field=AdaptiveFpsCapController.javaClass.getDeclaredField("cap"); field.isAccessible=true;field.set(null,state)
  PowerManager.targetFps=userEdit;PowerManager.applied.clear()
  val restore=AdaptiveFpsCapController.javaClass.getDeclaredMethod("restoreUserCap",String::class.java);restore.isAccessible=true
  restore.invoke(AdaptiveFpsCapController,"audit-disable")
  println("user edit=$userEdit after adaptive 60->45; stop applied=${PowerManager.applied} resulting target=${PowerManager.targetFps}")
 }
}
