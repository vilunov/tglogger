name := "tglogger"

version := "0.0"

scalaVersion := "2.12.4"

resolvers += "jitpack" at "https://jitpack.io"
libraryDependencies += "com.github.badoualy" % "kotlogram" % "1.0.0-RC3"
libraryDependencies += "org.scalikejdbc" %% "scalikejdbc" % "3.1.0"
libraryDependencies += "org.postgresql" % "postgresql" % "42.1.4"
libraryDependencies += "com.typesafe" % "config" % "1.3.1"
