name := "gpu-chisel"

version := "0.1.0"

scalaVersion := "2.13.17"

addCompilerPlugin("org.chipsalliance" % "chisel-plugin" % "7.2.0" cross CrossVersion.full)

libraryDependencies ++= Seq(
  "org.chipsalliance" %% "chisel" % "7.2.0",
  "org.scalatest" %% "scalatest" % "3.2.19" % "test"
)

Compile / scalacOptions ++= Seq("-language:reflectiveCalls")

Test / testOptions += Tests.Argument(TestFrameworks.ScalaTest, "-oD")
