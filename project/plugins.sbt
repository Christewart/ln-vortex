// bundle up Scala applications into packaging formats such as Docker,
// GraalVM native-image, executable JARs etc
addSbtPlugin("com.github.sbt" % "sbt-native-packager" % "1.9.7")

addSbtPlugin("com.eed3si9n" % "sbt-assembly" % "1.1.0")

addSbtPlugin("org.xerial.sbt" % "sbt-sonatype" % "3.9.10")

addSbtPlugin("org.scalameta" % "sbt-scalafmt" % "2.4.5")

addSbtPlugin("net.bzzt" % "sbt-reproducible-builds" % "0.30")

addSbtPlugin("com.thesamet" % "sbt-protoc" % "1.0.4")
