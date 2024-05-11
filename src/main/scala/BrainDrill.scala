import java.io.{BufferedReader, File, PrintWriter}
import java.nio.file.{Files, Paths}
import scala.annotation.tailrec
import scala.util.*
import scala.concurrent.Future
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration.*

object BrainDrill:

  // simple model for execution result
  enum Result:
    case Executed(output: String, exitCode: Int)
    case UnsupportedLanguage(lang: String)

  // simple model for grouping inputs to execute code inside container
  private case class ExecutionInputs(dockerImage: String, compiler: String, extension: String)

  // maps language to docker image, compiler/interpreter and file extension
  private val mappings: Map[String, ExecutionInputs] =
    Map(
      "python" -> ExecutionInputs(
        dockerImage = "python:3",
        compiler = "python",
        extension = ".py"
      ),
      "javascript" -> ExecutionInputs(
        dockerImage = "node:14",
        compiler = "node",
        extension = ".js"
      )
    )

  // runs code in docker container, async
  def runAsync(lang: String, code: String): Future[Result] =
    mappings get lang match
      case None => Future.successful(Result.UnsupportedLanguage(lang))
      case Some(input) =>
        // takes file name and builds command array for running docker container
        val cmd: String => Array[String] = file => Array(
          "docker",
          "run",
          "--rm",
          "-v",
          s"${System.getProperty("user.dir")}:/app",
          "-w",
          "/app",
          s"${input.dockerImage}",
          s"${input.compiler}",
          s"$file",
        )

        // fully async and non-blocking process
        for
          // create a file and write user code to it
          file <- file(s"$lang${input.extension}", code)
          // start docker container
          ps <- startProcess(cmd(file.getName))
          // start reading error and success channels concurrently
          (success, error) = read(ps.inputReader) -> read(ps.errorReader)
          // join success, error and exitCode in a non- blocking way
          ((success, error), exitCode) <- success.zip(error).zip(Future(ps.waitFor))
          // remove the file since it's not needed
          _ <- Future(file.delete())
          // return the execution result
        yield Result.Executed(
          output = if success.nonEmpty then success else error,
          exitCode = exitCode
        )

  // creates file and writes content to it, all done async
  private def file(name: String, content: String) =
    for
      file <- Future(File(name))
      _    <- Future(Using.resource(PrintWriter(name))(_.write(content)))
    yield file

  // starts the docker process in async way
  private def startProcess(commands: Array[String]) =
    Future:
      Runtime
        .getRuntime
        .exec(commands)

  // reads the full output from source in async way with resource-safety enabled
  private def read(reader: BufferedReader) =
    Future:
      Using.resource(reader): reader =>

        @tailrec
        def loop(accumulated: String = ""): String =
          readLine(reader) match
            case Some(line) => loop(s"$accumulated$line\n")
            case None => accumulated

        loop()

  // reads a single line from source with optionality in mind
  private def readLine(reader: BufferedReader) =
    Try(reader.readLine)
      .toOption
      .filter(line => line != null && line.nonEmpty)