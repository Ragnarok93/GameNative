package app.gamenative.diagnostics
import app.gamenative.benchmark.TurnipExperimentOptions
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
class TurnipExperimentOptionsTest {
 @Test fun unchangedPreservesEnvironment() {
  assertEquals("TU_DEBUG=noconform X=1",TurnipExperimentOptions.apply("TU_DEBUG=noconform X=1",JSONObject()))
 }
 @Test fun profileAndTraceAreFixedAndRunScoped() {
  val run="a".repeat(32)
  val out=TurnipExperimentOptions.apply("TU_DEBUG=noconform",JSONObject().put("run_id",run).put("turnip_autotune","profiled").put("gpu_trace",true))
  assertTrue(out.contains("TU_AUTOTUNE_ALGO=profiled"));assertTrue(out.contains("MESA_GPU_TRACES=print"))
  assertTrue(out.contains("gpu-$run-%p-%i.log"));assertTrue(out.startsWith("TU_DEBUG=noconform "))
 }
 @Test fun rejectInvalidAlgorithmPathAndExistingVariable() {
  for (request in listOf(JSONObject().put("turnip_autotune","arbitrary"),JSONObject().put("gpu_trace",true).put("run_id","../escape"),JSONObject().put("turnip_autotune","profiled"))) {
   try { TurnipExperimentOptions.apply("TU_AUTOTUNE_ALGO=bandwidth",request);fail("Must reject") } catch (expected:IllegalArgumentException) {}
  }
 }
}
