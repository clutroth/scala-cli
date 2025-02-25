package scala.cli.commands

import caseapp.Name
import caseapp.core.app.Command
import caseapp.core.complete.{Completer, CompletionItem}
import caseapp.core.help.{Help, HelpFormat}
import caseapp.core.parser.Parser
import caseapp.core.util.Formatter
import caseapp.core.{Arg, Error, RemainingArgs}
import coursier.core.{Repository, Version}
import dependency.*

import scala.annotation.tailrec
import scala.build.EitherCps.{either, value}
import scala.build.compiler.SimpleScalaCompiler
import scala.build.errors.BuildException
import scala.build.internal.{Constants, Runner}
import scala.build.options.{BuildOptions, Scope}
import scala.build.{Artifacts, Logger, Positioned, ReplArtifacts}
import scala.cli.commands.shared.{HasLoggingOptions, ScalacOptions, SharedOptions}
import scala.cli.commands.util.CommandHelpers
import scala.cli.commands.util.ScalacOptionsUtil.*
import scala.cli.{CurrentParams, ScalaCli}
import scala.util.{Properties, Try}

abstract class ScalaCommand[T <: HasLoggingOptions](implicit myParser: Parser[T], help: Help[T])
    extends Command()(myParser, help)
    with NeedsArgvCommand with CommandHelpers with RestrictableCommand[T] {

  def sharedOptions(t: T): Option[SharedOptions] = // hello borked unused warning
    None
  override def hasFullHelp = true

  protected var argvOpt = Option.empty[Array[String]]
  override def setArgv(argv: Array[String]): Unit = {
    argvOpt = Some(argv)
  }

  /** @return the actual Scala CLI program name which was run */
  protected def progName: String = ScalaCli.progName

  /** @return the actual Scala CLI runner name which was run */
  protected def fullRunnerName = ScalaCli.fullRunnerName

  /** @return the actual Scala CLI base runner name, for SIP it is scala otherwise scala-cli */
  protected def baseRunnerName = ScalaCli.baseRunnerName

  // TODO Manage to have case-app give use the exact command name that was used instead
  /** The actual sub-command name that was used. If the sub-command name is a list of strings, space
    * is used as the separator. If [[argvOpt]] hasn't been defined, it defaults to [[name]].
    */
  protected def actualCommandName: String =
    argvOpt.map { argv =>
      @tailrec
      def validCommand(potentialCommandName: List[String]): Option[List[String]] =
        if potentialCommandName.isEmpty then None
        else
          names.find(_ == potentialCommandName) match {
            case cmd @ Some(_) => cmd
            case _             => validCommand(potentialCommandName.dropRight(1))
          }

      val maxCommandLength: Int    = names.map(_.length).max max 1
      val maxPotentialCommandNames = argv.slice(1, maxCommandLength + 1).toList
      validCommand(maxPotentialCommandNames).getOrElse(List(""))
    }.getOrElse(List(name)).mkString(" ")

  protected def actualFullCommand: String =
    if actualCommandName.nonEmpty then s"$progName $actualCommandName" else progName

  override def error(message: Error): Nothing = {
    System.err.println(
      s"""${message.message}
         |
         |To list all available options, run
         |  ${Console.BOLD}$actualFullCommand --help${Console.RESET}""".stripMargin
    )
    sys.exit(1)
  }

  // FIXME Report this in case-app default NameFormatter
  override lazy val nameFormatter: Formatter[Name] = {
    val parent = super.nameFormatter
    (t: Name) =>
      if (t.name.startsWith("-")) t.name
      else parent.format(t)
  }

  override def completer: Completer[T] = {
    val parent = super.completer
    new Completer[T] {
      def optionName(prefix: String, state: Option[T]): List[CompletionItem] =
        parent.optionName(prefix, state)
      def optionValue(arg: Arg, prefix: String, state: Option[T]): List[CompletionItem] = {
        val candidates = arg.name.name match {
          case "dependency" =>
            state.flatMap(sharedOptions).toList.flatMap { sharedOptions =>
              val logger = sharedOptions.logger
              val cache  = sharedOptions.coursierCache
              val sv = sharedOptions.buildOptions().orExit(logger)
                .scalaParams
                .toOption
                .flatten
                .map(_.scalaVersion)
                .getOrElse(Constants.defaultScalaVersion)
              val (fromIndex, completions) = cache.logger.use {
                coursier.complete.Complete(cache)
                  .withInput(prefix)
                  .withScalaVersion(sv)
                  .complete()
                  .unsafeRun()(cache.ec)
              }
              if (completions.isEmpty) Nil
              else {
                val prefix0 = prefix.take(fromIndex)
                val values  = completions.map(c => prefix0 + c)
                values.map { str =>
                  CompletionItem(str)
                }
              }
            }
          case "repository" => Nil // TODO
          case _            => Nil
        }
        candidates ++ parent.optionValue(arg, prefix, state)
      }
      def argument(prefix: String, state: Option[T]): List[CompletionItem] =
        parent.argument(prefix, state)
    }
  }

  def maybePrintGroupHelp(options: T): Unit =
    for (shared <- sharedOptions(options))
      shared.helpGroups.maybePrintGroupHelp(help, helpFormat)

  /** Print `scalac` output if passed options imply no inputs are necessary and raw `scalac` output
    * is required instead. (i.e. `--scalac-option -help`)
    * @param options
    *   command options
    */
  def maybePrintSimpleScalacOutput(options: T, buildOptions: BuildOptions): Unit =
    for {
      shared <- sharedOptions(options)
      scalacOptions        = shared.scalac.scalacOption
      updatedScalacOptions = scalacOptions.withScalacExtraOptions(shared.scalacExtra)
      if updatedScalacOptions.exists(ScalacOptions.ScalacPrintOptions)
      logger = shared.logger
      artifacts      <- buildOptions.artifacts(logger, Scope.Main).toOption
      scalaArtifacts <- artifacts.scalaOpt
      compilerClassPath   = scalaArtifacts.compilerClassPath
      scalaVersion        = scalaArtifacts.params.scalaVersion
      compileClassPath    = artifacts.compileClassPath
      simpleScalaCompiler = SimpleScalaCompiler("java", Nil, scaladoc = false)
      javacOptions        = buildOptions.javaOptions.javacOptions.map(_.value)
      javaHome            = buildOptions.javaHomeLocation().value
    } {
      val exitCode = simpleScalaCompiler.runSimpleScalacLike(
        scalaVersion,
        Option(javaHome),
        javacOptions,
        updatedScalacOptions,
        compileClassPath,
        compilerClassPath,
        logger
      )
      sys.exit(exitCode)
    }

  def maybePrintToolsHelp(options: T, buildOptions: BuildOptions): Unit =
    for {
      shared <- sharedOptions(options)
      logger = shared.logger
      artifacts      <- buildOptions.artifacts(logger, Scope.Main).toOption
      scalaArtifacts <- artifacts.scalaOpt
      scalaParams = scalaArtifacts.params
      if shared.helpGroups.helpScaladoc || shared.helpGroups.helpRepl || shared.helpGroups.helpScalafmt
    } {
      val exitCode: Either[BuildException, Int] = either {
        val (classPath: Seq[os.Path], mainClass: String) =
          if (shared.helpGroups.helpScaladoc) {
            val docArtifacts = value {
              Artifacts.fetch(
                Positioned.none(Seq(dep"org.scala-lang::scaladoc:${scalaParams.scalaVersion}")),
                value(buildOptions.finalRepositories),
                Some(scalaParams),
                logger,
                buildOptions.finalCache,
                None
              )
            }
            docArtifacts.files.map(os.Path(_, os.pwd)) -> "dotty.tools.scaladoc.Main"
          }
          else if (shared.helpGroups.helpRepl) {
            val initialBuildOptions = buildOptionsOrExit(options)
            val artifacts = initialBuildOptions.artifacts(logger, Scope.Main).orExit(logger)
            val replArtifacts = value {
              ReplArtifacts.default(
                scalaParams,
                artifacts.userDependencies,
                Nil,
                logger,
                buildOptions.finalCache,
                Nil,
                None
              )
            }
            replArtifacts.replClassPath -> replArtifacts.replMainClass
          }
          else {
            val fmtArtifacts = value {
              Artifacts.fetch(
                Positioned.none(Seq(
                  dep"${Constants.scalafmtOrganization}:${Constants.scalafmtName}:${Constants.defaultScalafmtVersion}"
                )),
                value(buildOptions.finalRepositories),
                Some(scalaParams),
                logger,
                buildOptions.finalCache,
                None
              )
            }
            fmtArtifacts.files.map(os.Path(_, os.pwd)) -> "org.scalafmt.cli.Cli"
          }
        val retCode = Runner.runJvm(
          buildOptions.javaHome().value.javaCommand,
          Nil,
          classPath,
          mainClass,
          Seq("-help"),
          logger
        ).waitFor()
        retCode
      }
      sys.exit(exitCode.orExit(logger))
    }

  override def helpFormat: HelpFormat =
    HelpFormat.default().copy(
      sortedGroups = Some(Seq(
        "Help",
        "Scala",
        "Java",
        "Repl",
        "Package",
        "Metabrowse server",
        "Logging",
        "Runner"
      )),
      sortedCommandGroups = Some(Seq(
        "Main",
        "Miscellaneous",
        ""
      )),
      hiddenGroups = Some(Seq(
        "Scala.js",
        "Scala Native"
      )),
      terminalWidthOpt =
        if (Properties.isWin)
          if (coursier.paths.Util.useJni())
            Try(coursier.jniutils.WindowsAnsiTerminal.terminalSize()).toOption.map(
              _.getWidth
            ).orElse {
              val fallback = 120
              if (java.lang.Boolean.getBoolean("scala.cli.windows-terminal.verbose"))
                System.err.println(s"Could not get terminal width, falling back to $fallback")
              Some(fallback)
            }
          else None
        else
          // That's how Ammonite gets the terminal width, but I'd rather not spawn a sub-process upfront in Scala CLI…
          //   val pathedTput = if (os.isFile(os.Path("/usr/bin/tput"))) "/usr/bin/tput" else "tput"
          //   val width = os.proc("sh", "-c", s"$pathedTput cols 2>/dev/tty").call(stderr = os.Pipe).out.trim().toInt
          //   Some(width)
          // Ideally, we should do an ioctl, like jansi does here:
          //   https://github.com/fusesource/jansi/blob/09722b7cccc8a99f14ac1656db3072dbeef34478/src/main/java/org/fusesource/jansi/AnsiConsole.java#L344
          // This requires writing our own minimal JNI library, that publishes '.a' files too for static linking in the executable of Scala CLI.
          None
    )

  /** @param options
    *   command-specific [[T]] options
    * @return
    *   Tries to create BuildOptions based on [[sharedOptions]] and exits on error. Override to
    *   change this behaviour.
    */
  def buildOptions(options: T): Option[BuildOptions] =
    sharedOptions(options).map(shared => shared.buildOptions().orExit(shared.logger))

  protected def buildOptionsOrExit(options: T): BuildOptions =
    buildOptions(options).getOrElse {
      sharedOptions(options).foreach(_.logger.debug("build options could not be initialized"))
      sys.exit(1)
    }

  /** This should be overridden instead of [[run]] when extending [[ScalaCommand]].
    *
    * @param options
    *   the command's specific set of options
    * @param remainingArgs
    *   arguments remaining after parsing options
    */
  def runCommand(options: T, remainingArgs: RemainingArgs, logger: Logger): Unit

  /** This implementation is final. Override [[runCommand]] instead. This logic is invoked at the
    * start of running every [[ScalaCommand]].
    */
  final override def run(options: T, remainingArgs: RemainingArgs): Unit = {
    CurrentParams.verbosity = options.logging.verbosity
    maybePrintGroupHelp(options)
    buildOptions(options).foreach { bo =>
      maybePrintSimpleScalacOutput(options, bo)
      maybePrintToolsHelp(options, bo)
    }
    runCommand(options, remainingArgs, options.logging.logger)
  }
}
