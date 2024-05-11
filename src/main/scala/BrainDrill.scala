import java.io.{BufferedReader, File, PrintWriter}
import java.nio.file.{Files, Paths}
import scala.annotation.tailrec
import scala.util.*
import scala.concurrent.Future
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration.*

object BrainDrill:

  enum Result:
    case Executed(output: String, exitCode: Int)
    case UnsupportedLanguage(lang: String)

  import Result.*

  // maps language to docker image, compiler/interpreter and file extension
  private val mappings: Map[String, (String, String, String)] =
    Map(
      "python" -> ("python:3", "python", ".py")
    )

  def execute(lang: String, code: String): Future[Result] =

    mappings get lang match
      case None => Future.successful(UnsupportedLanguage(lang))
      case Some((img, compiler, extension)) =>

        val cmd: String => Array[String] = file => Array(
          "docker",
          "run",
          "--rm",
          "-v",
          s"${System.getProperty("user.dir")}:/app",
          "-w",
          "/app",
          s"$img",
          s"$compiler",
          s"$file",
        )

        for
          file <- file(s"$lang$extension", code)
          ps <- startProcess(cmd(file.getName))
          (success, error) = read(ps.inputReader) -> read(ps.errorReader)
          ((success, error), exitCode) <- success.zip(error).zip(Future(ps.waitFor))
           _ <- Future(file.delete())
        yield Executed(
          output = if success.nonEmpty then success else error,
          exitCode = exitCode
        )

  private def file(name: String, content: String): Future[File] =
    for
      file <- Future(File(name))
      _    <- Future(Using.resource(PrintWriter(name))(_.write(content)))
    yield file

  private def startProcess(commands: Array[String]) =
    Future:
      Runtime
        .getRuntime
        .exec(commands)

  private def read(reader: BufferedReader) =
    Future:
      Using.resource(reader): reader =>

        @tailrec
        def loop(accumulated: String = ""): String =
          readLine(reader) match
            case Some(line) => loop(s"$accumulated$line\n")
            case None => accumulated

        loop()

  private def readLine(reader: BufferedReader) =
    Try(reader.readLine)
      .toOption
      .filter(line => line != null && line.nonEmpty)