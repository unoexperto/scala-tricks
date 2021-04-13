# scala-tricks
Collection of helper classes and methods I use in different projects

Maven artifacts are published on AWS S3.

```
lazy val root = (project in file("."))
  .settings(
    name := "test_scala",
    resolvers ++= Seq(
      "walkmind maven" at "https://walkmind-maven.s3.amazonaws.com/"
    ),
    libraryDependencies ++= Seq(
      "com.walkmind" %% "scala-tricks" % "2.42" withSources()
    )
  )
```
