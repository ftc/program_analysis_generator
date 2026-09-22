# program-analysis-generator

The goal of this project is to build a structure by which large language models may be used to generate the difficult to write pieces of a program analysis and rely on strict correctness tests to avoid unsoundness.
The fundamental principle of this project is that every step of a program analysis must adhere to the soundness criteria.
Implementations of each small scale piece of a program analysis must have direct ways of testing against observed runtime behavior.

The analysis works in two phases:
1. A forward, over-approximate, flow-insensitive analysis (e.g. an anderson's analysis or steensgaard analysis).  This pass computes a rough approximation of the call graph and the aliasing relationships between variables, pointers, etc.
2. A backwards analysis that starts at the location of a defect capturing the failure condition and works backwards through the execution of the application to reach a proof if the initial state of the program can be excluded from a fixed point or an alarm if the initial point is reached.  The backward analysis can be over-approximate to produce a proof or under-approximate to produce a must-witness. In either case it is guided by the forward over-approximate analysis.

**Humans never write transfer functions**
A key part of this project is that (ideally) humans should never directly look at the abstract domain implementations.
The key insight that should make this possible is that the soundness of the abstract domain defines a way to test every step of the transfer function.
Each abstract domain must implement a "contains" method that checks an observed state for containment in an abstraction.
Abstract domains are tested by running sample programs and observing the pre and post states of commands then testing the transfer function against the soundness condition.

[Add example of a simple separation logic abstract domain, the soudness condition, and how the heap can be observed by a debugger and be used to test a transfer function. This example should follow the backward semantics of the Historia work.]

Scala 3 (LTS 3.3.6) project built with sbt 1.11.7.

## Layout

```
build.sbt                     build definition
project/build.properties      sbt version
src/main/scala/pag/Main.scala entry point (pag.Main)
src/test/scala/pag/           MUnit test suites
```

## Setup on a new machine

> Windows is not supported. Use macOS or Linux (WSL2 counts as Linux).

You only need a JDK and sbt. The Scala compiler, the sbt version pinned in
`project/build.properties`, and every library dependency are downloaded
automatically on the first build.

### 1. Install a JDK (21 LTS recommended)

```sh
# macOS (Homebrew)
brew install openjdk@21

# Debian / Ubuntu
sudo apt install openjdk-21-jdk
```

Check that it is on the `PATH`:

```sh
java -version   # expect 21.x (17+ also works)
```

If you manage several JDKs with `jenv`, `sdkman`, or `asdf`, select 21 for this
directory before building — for example `jenv local 21`.

### 2. Install sbt

```sh
# macOS (Homebrew)
brew install sbt

# Debian / Ubuntu
sudo apt install sbt        # or: https://www.scala-sbt.org/download
```

An alternative that installs sbt, scala, and scala-cli together is
[Coursier](https://get-coursier.io/docs/cli-installation): `cs setup`.

The `sbt` command is only a launcher — it reads `project/build.properties` and
fetches sbt 1.11.7 itself, so a different launcher version is fine.

### 3. Get the code and build

```sh
git clone <repository-url> program_analysis_generator
cd program_analysis_generator
sbt test
```

The first run downloads sbt, the Scala 3.3.6 compiler, and MUnit into
`~/.cache/coursier` (`~/Library/Caches/Coursier` on macOS) and takes a few
minutes. Later builds start in seconds. A successful setup ends with:

```
[info] Passed: Total 3, Failed 0, Errors 0, Passed 3
[success] Total time: ...
```

Then confirm the entry point runs:

```sh
sbt "run Scala"   # prints: Hello, Scala!
```

### 4. Editor setup (optional)

- **VS Code**: install the [Metals](https://marketplace.visualstudio.com/items?itemName=scalameta.metals)
  extension, open this directory, and accept the "Import build" prompt.
- **IntelliJ IDEA**: install the Scala plugin, then *File → Open* this directory
  and choose the sbt project when prompted.
- **Formatting**: `.scalafmt.conf` pins scalafmt 3.8.3. Metals and IntelliJ pick
  it up automatically; from the command line, run `scalafmt` via
  [Coursier](https://scalameta.org/scalafmt/docs/installation.html).

### Offline / air-gapped machines

Dependencies resolve from Maven Central over HTTPS. Behind a proxy, set
`JAVA_OPTS="-Dhttps.proxyHost=... -Dhttps.proxyPort=..."`, or copy a warm
Coursier cache from a machine that has already built the project.

## Common commands

```sh
sbt compile          # compile
sbt test             # run the unit tests
sbt run              # run Main with no arguments
sbt "run Scala"      # run Main with arguments
sbt console          # Scala REPL with the project on the classpath
sbt ~test            # re-run tests on every file change
sbt clean            # delete build output under target/
```
