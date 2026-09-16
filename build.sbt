name := "gpu-chisel"

version := "0.1.0"

scalaVersion := "2.13.18"

val chiselVersion = "7.15.0"

addCompilerPlugin("org.chipsalliance" % "chisel-plugin" % chiselVersion cross CrossVersion.full)

libraryDependencies ++= Seq(
  "org.chipsalliance" %% "chisel" % chiselVersion,
  "org.scalatest" %% "scalatest" % "3.2.19" % "test"
)

Compile / scalacOptions ++= Seq("-language:reflectiveCalls")

Test / testOptions += Tests.Argument(TestFrameworks.ScalaTest, "-oD")

// FloatFMA is vendored at src/main/scala/yunsuan/fpu/FloatFMA.scala (a local
// copy of YunSuan's FMA with an explicit intermediate register for the
// infinity-sign selection, used by the FpuBackend timing closure) so the
// build is self-contained and reproducible.  Its small compatibility
// definitions live in src/main/scala/yunsuan/FmaCompatibility.scala; the
// rest of YunSuan is not pulled into this build.
