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
    case UnsupportedLang(lang: String)

  // simple model for grouping inputs to execute code inside container
  private case class ExecutionInputs(dockerImage: String, compiler: String, extension: String)

  // maps language to its ExecutionInputs
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

  // attempts to run code in docker container in async and non-blocking way
  def runAsync(lang: String, code: String): Future[Result] =
    mappings get lang match
      case None => Future.successful(Result.UnsupportedLang(lang))
      case Some(input) =>
        for
          // create a file and write user code to it
          file <- file(s"$lang${input.extension}", code)
          // start docker container
          ps <- startProcess:
            Array(
              "docker",
              "run",
              "--rm", // remove container after it's done
              "-v",
              s"${System.getProperty("user.dir")}:/app",
              "-w",
              "/app",
              s"${input.dockerImage}",
              s"${input.compiler}", // e.g scala
              s"$file", // e.g Main.scala
            )
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