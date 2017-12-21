name := "tglogger"
version := "0.0"
organization := "me.vilunov"

scalaVersion := "2.12.4"

resolvers += "jitpack" at "https://jitpack.io"

libraryDependencies ++= Seq(
  "com.github.badoualy" %  "kotlogram"   % "1.0.0-RC3",
  "org.scalikejdbc"     %% "scalikejdbc-async" % "0.9.0",
  "org.postgresql"      %  "postgresql"  % "42.1.4",
  "com.typesafe"        %  "config"      % "1.3.1",

  "com.typesafe.akka" %% "akka-actor"    % "2.5.7",
  "com.typesafe.akka" %% "akka-stream"   % "2.5.7",

  "com.typesafe.akka" %% "akka-http"            % "10.0.11",
  "com.typesafe.akka" %% "akka-http-spray-json" % "10.0.11",

  "com.typesafe.akka" %% "akka-testkit"  % "2.5.7",
  "org.scalatest"     %% "scalatest"     % "3.0.4" % "test"
)
