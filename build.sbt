name := "wiser-spider"

version := "0.1.2"

organization := "com.nowanswers"

scalaVersion := "2.10.1"

fork in run := true

libraryDependencies ++= Seq(
  "com.typesafe.akka" %% "akka-actor" % "2.1.2",
  "com.typesafe.akka" %% "akka-slf4j" % "2.1.2",
  "com.typesafe.akka" %% "akka-testkit" % "2.1.2" % "test",
  "org.ccil.cowan.tagsoup" % "tagsoup" % "1.2.1" withSources(),
  "ch.qos.logback" % "logback-classic" % "1.0.7",
  "org.mongodb" %% "casbah" % "2.5.0",
  "com.novus" %% "salat" % "1.9.2-SNAPSHOT",
  "com.github.tototoshi" %% "scala-csv" % "0.7.0",
  "io.spray" % "spray-client" % "1.1-M7",
  "commons-codec" % "commons-codec" % "1.7"
)

resolvers ++= Seq(
        "spray repo" at "http://repo.spray.io",
        "Typesafe Repository" at "http://repo.typesafe.com/typesafe/releases/",
        "Sonatype OSS Snapshots" at "https://oss.sonatype.org/content/repositories/snapshots"
)
