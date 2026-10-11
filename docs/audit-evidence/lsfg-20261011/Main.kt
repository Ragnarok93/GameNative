package app.gamenative.powercontrol
import app.gamenative.powercontrol.autotuning.AdaptiveFpsCap
fun main() {
 val controller = AdaptiveFpsCapController
 val capField = controller.javaClass.getDeclaredField("cap").apply { isAccessible=true }
 val ownedField = controller.javaClass.getDeclaredField("lastOwnedEffectiveFps").apply { isAccessible=true }
 val editField = controller.javaClass.getDeclaredField("lastOwnedUserEditGeneration").apply { isAccessible=true }
 val restore = controller.javaClass.getDeclaredMethod("restoreUserCap",String::class.java).apply { isAccessible=true }
 fun seedOwnedAdaptiveCap() {
  val state=AdaptiveFpsCap(); state.observeCap(60)
  repeat(30) { state.onCycle(30f,true,true)?.let(state::commit) }
  check(state.effectiveCapFps==45)
  capField.set(controller,state)
  PowerManager.targetFps=45
  ownedField.set(controller,45)
  editField.set(controller,PowerManager.currentUserFpsCapEditGeneration())
 }
 for(userEdit in listOf(0,90,45)) {
  PowerManager.reset()
  seedOwnedAdaptiveCap()
  PowerManager.markUserFpsCapEdited()
  PowerManager.targetFps=userEdit
  PowerManager.applied.clear()
  restore.invoke(controller,"audit-disable")
  check(PowerManager.applied.isEmpty()) { "stale restore replaced user edit $userEdit" }
  check(PowerManager.targetFps==userEdit)
  println("PASS edit=$userEdit retained cap="+PowerManager.targetFps)
 }
 PowerManager.reset()
 seedOwnedAdaptiveCap()
 restore.invoke(controller,"audit-clean-stop")
 check(PowerManager.applied==listOf(60)) { "owned cap was not restored" }
 println("PASS legitimate owned adaptive 45 -> original 60")
 PowerManager.reset()
 seedOwnedAdaptiveCap()
 PowerManager.generation++ // queued policy switch between observation and command
 check(!PowerManager.applyFpsCapToEngines(40,expectedGeneration=0L))
 check(PowerManager.applied.isEmpty())
 println("PASS stale observed policy cannot submit cap")
}
